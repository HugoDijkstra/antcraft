#!/usr/bin/env python3
"""AntCraft world analyzer (stdlib only).

Usage: python3 -I analyze.py <world dir> <server.log> [--portal-only]
Loads dimensions/minecraft/overworld/region/*.mca (or region/*.mca) and entities,
then runs the AntCraft structural checks and prints a JSON summary at the end.
Offline counterpart of /antcraft selfcheck. Assumes the default 20 x 300 strip; the server.log
must contain the "holes at ..." line AntCraft logs on start.
"""
import glob, gzip, json, math, os, re, struct, sys, zlib
from array import array
from collections import Counter, defaultdict

# ---------------------------------------------------------------- NBT
class R:
    __slots__ = ("b", "i")
    def __init__(self, b): self.b = b; self.i = 0
    def take(self, n):
        v = self.b[self.i:self.i + n]; self.i += n; return v

def _str(r):
    n = struct.unpack(">H", r.take(2))[0]
    return r.take(n).decode("utf-8", "replace")

def _payload(r, t):
    if t == 1: return struct.unpack(">b", r.take(1))[0]
    if t == 2: return struct.unpack(">h", r.take(2))[0]
    if t == 3: return struct.unpack(">i", r.take(4))[0]
    if t == 4: return struct.unpack(">q", r.take(8))[0]
    if t == 5: return struct.unpack(">f", r.take(4))[0]
    if t == 6: return struct.unpack(">d", r.take(8))[0]
    if t == 7:
        n = struct.unpack(">i", r.take(4))[0]; return r.take(n)
    if t == 8: return _str(r)
    if t == 9:
        et = r.take(1)[0]; n = struct.unpack(">i", r.take(4))[0]
        return [_payload(r, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = r.take(1)[0]
            if tt == 0: return d
            name = _str(r); d[name] = _payload(r, tt)
    if t == 11:
        n = struct.unpack(">i", r.take(4))[0]; return array("i", r.take(4 * n)) if False else struct.unpack(">%di" % n, r.take(4 * n))
    if t == 12:
        n = struct.unpack(">i", r.take(4))[0]; return ("LONGS", r.take(8 * n))  # raw big-endian
    raise ValueError("bad tag %d" % t)

def read_nbt(b):
    r = R(b); t = r.take(1)[0]
    if t != 10: raise ValueError("root not compound")
    _str(r)  # root name (may be empty)
    return _payload(r, 10)

def read_region(path):
    with open(path, "rb") as f: data = f.read()
    out = []
    for i in range(1024):
        off = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
        sector, count = off >> 8, off & 0xFF
        if sector == 0: continue
        p = sector * 4096
        ln = struct.unpack(">I", data[p:p + 4])[0]
        comp = data[p + 4]
        raw = data[p + 5:p + 4 + ln]
        if comp & 0x80:
            raise ValueError("external chunk (.mcc) not supported: %s idx %d" % (path, i))
        if comp == 2: raw = zlib.decompress(raw)
        elif comp == 1: raw = gzip.decompress(raw)
        elif comp == 3: pass
        else: raise ValueError("compression %d unsupported" % comp)
        out.append(read_nbt(raw))
    return out

# ---------------------------------------------------------------- block storage
LO = bytes(i & 15 for i in range(256)); HI = bytes(i >> 4 for i in range(256))

def unpack_indices(raw_be, bits):
    a = array("Q"); a.frombytes(raw_be)
    if sys.byteorder == "little": a.byteswap()
    le = a.tobytes()  # each long little endian -> byte0 holds lowest bits
    if bits == 4:
        out = bytearray(2 * len(le))
        out[0::2] = le.translate(LO); out[1::2] = le.translate(HI)
        return out[:4096]
    if bits == 8:
        return bytearray(le[:4096])
    per = 64 // bits; mask = (1 << bits) - 1
    out = array("H")
    for v in a:
        for _ in range(per):
            out.append(v & mask); v >>= bits
    return out[:4096]

def state_key(entry):
    if isinstance(entry, dict) and "" in entry and len(entry) == 1:  # heterogeneous list wrapper
        entry = entry[""]
    if isinstance(entry, str):  # 26.x compact palette form: "minecraft:x" or "minecraft:x[k=v,...]"
        if "[" in entry:
            name, rest = entry.split("[", 1)
            props = tuple(sorted(tuple(p.split("=", 1)) for p in rest.rstrip("]").split(",") if p))
            return (name, props)
        return (entry, ())
    name = entry.get("Name", entry.get("id"))
    props = entry.get("Properties", entry.get("properties"))
    return (name, tuple(sorted(props.items())) if props else ())

class World:
    def __init__(self):
        self.sections = {}  # (cx, cy, cz) -> (palette[list of (name, props)], indices or None)
        self.chunks = {}    # (cx, cz) -> status
        self.block_entities = {}  # (x,y,z) -> nbt
        self.entities = []
        self.sky = {}; self.biomes = {}; self.lighton = {}

    def load(self, region_dir):
        for path in sorted(glob.glob(os.path.join(region_dir, "*.mca"))):
            for c in read_region(path):
                cx, cz = c["xPos"], c["zPos"]
                self.chunks[(cx, cz)] = c.get("Status", "?")
                self.lighton[(cx, cz)] = c.get("isLightOn")
                for s in c.get("sections", []):
                    if "SkyLight" in s: self.sky[(cx, s["Y"], cz)] = s["SkyLight"]
                    bi = s.get("biomes")
                    if bi is not None:
                        bp = [state_key(e)[0] for e in bi["palette"]]
                        if len(bp) == 1: self.biomes[(cx, s["Y"], cz)] = (bp, None)
                        else:
                            bits = max(1, math.ceil(math.log2(len(bp))))
                            a = array("Q"); a.frombytes(bi["data"][1])
                            if sys.byteorder == "little": a.byteswap()
                            per = 64 // bits; mask = (1 << bits) - 1; out = []
                            for v in a:
                                for _ in range(per): out.append(v & mask); v >>= bits
                            self.biomes[(cx, s["Y"], cz)] = (bp, out[:64])
                    bs = s.get("block_states")
                    if bs is None: continue
                    pal = [state_key(e) for e in bs["palette"]]
                    if len(pal) == 1:
                        idx = None
                    else:
                        bits = max(4, math.ceil(math.log2(len(pal))))
                        idx = unpack_indices(bs["data"][1], bits)
                    self.sections[(cx, s["Y"], cz)] = (pal, idx)
                for be in c.get("block_entities", []):
                    self.block_entities[(be["x"], be["y"], be["z"])] = be

    def load_entities(self, ent_dir):
        for path in sorted(glob.glob(os.path.join(ent_dir, "*.mca"))):
            for c in read_region(path):
                for e in c.get("Entities", []):
                    self.entities.append(e)

    def block(self, x, y, z):
        sec = self.sections.get((x >> 4, y >> 4, z >> 4))
        if sec is None: return None
        pal, idx = sec
        if idx is None: return pal[0]
        return pal[idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]]

    def name(self, x, y, z):
        b = self.block(x, y, z)
        return None if b is None else b[0]

    def section_counts(self, cx, cy, cz, x0, x1, z0, z1, y0, y1):
        """Counter of block names within the given absolute box restricted to one section."""
        sec = self.sections.get((cx, cy, cz))
        cnt = Counter()
        if sec is None: return cnt, False
        pal, idx = sec
        bx, by, bz = cx * 16, cy * 16, cz * 16
        xs = range(max(x0, bx), min(x1, bx + 15) + 1)
        zs = range(max(z0, bz), min(z1, bz + 15) + 1)
        ys = range(max(y0, by), min(y1, by + 15) + 1)
        if not xs or not zs or not ys: return cnt, True
        if idx is None:
            cnt[pal[0][0]] += len(xs) * len(ys) * len(zs); return cnt, True
        pc = Counter()
        for y in ys:
            for z in zs:
                base = ((y & 15) << 8) | ((z & 15) << 4)
                for x in xs:
                    pc[idx[base | (x & 15)]] += 1
        for k, v in pc.items(): cnt[pal[k][0]] += v
        return cnt, True

    def box_counts(self, x0, x1, y0, y1, z0, z1):
        cnt = Counter(); missing = 0
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            for cz in range(z0 >> 4, (z1 >> 4) + 1):
                for cy in range(y0 >> 4, (y1 >> 4) + 1):
                    c, ok = self.section_counts(cx, cy, cz, x0, x1, z0, z1, y0, y1)
                    if not ok: missing += 1
                    cnt.update(c)
        return cnt, missing

    def find(self, names, x0, x1, y0, y1, z0, z1):
        """List positions of blocks whose name is in names within the box (section-skipping)."""
        res = []
        for (cx, cy, cz), (pal, idx) in self.sections.items():
            bx, by, bz = cx * 16, cy * 16, cz * 16
            if bx > x1 or bx + 15 < x0 or by > y1 or by + 15 < y0 or bz > z1 or bz + 15 < z0: continue
            want = {i for i, p in enumerate(pal) if p[0] in names}
            if not want: continue
            for i in range(4096):
                pi = 0 if idx is None else idx[i]
                if pi in want:
                    x, y, z = bx + (i & 15), by + (i >> 8), bz + ((i >> 4) & 15)
                    if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:
                        res.append((x, y, z, pal[pi]))
        return res

# ---------------------------------------------------------------- geometry (mirrors Strip.java)
WORLD_MIN_Y, WORLD_MAX_Y = -2032, 335
MIN_SEC, MAX_SEC = -127, 20
def floor_base(k): return -64 - 256 * k
def floor_top(k): return floor_base(k) + 2
def layer_bottom(l): return floor_top(l) + 1 if l < 8 else -2028 + 1
def layer_top(l): return floor_base(l - 1) - 1
def forest_floor(l):
    b, t = layer_bottom(l), layer_top(l); return b + int((t - b) * 0.45)
def layer_at(y):
    if y > floor_top(0): return 0
    for l in range(1, 9):
        if layer_bottom(l) <= y <= layer_top(l): return l
    return -1

LOGS = re.compile(r"minecraft:(.*_log|.*_stem|.*_wood|.*_hyphae|mushroom_stem|mangrove_roots|muddy_mangrove_roots)$")
LEAVES = re.compile(r"minecraft:(.*_leaves|brown_mushroom_block|red_mushroom_block)$")
ORE = re.compile(r"minecraft:(.*_ore|ancient_debris|obsidian)$")
CITY = {"minecraft:reinforced_deepslate", "minecraft:sculk_shrieker", "minecraft:sculk_sensor", "minecraft:sculk_catalyst",
        "minecraft:deepslate_tiles", "minecraft:deepslate_tile_slab", "minecraft:deepslate_tile_stairs", "minecraft:deepslate_tile_wall",
        "minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks", "minecraft:cracked_deepslate_tiles",
        "minecraft:polished_deepslate", "minecraft:chiseled_deepslate", "minecraft:soul_lantern", "minecraft:soul_fire",
        "minecraft:gray_wool", "minecraft:blue_ice", "minecraft:chest", "minecraft:candle", "minecraft:polished_basalt",
        "minecraft:dark_oak_planks", "minecraft:dark_oak_fence", "minecraft:smooth_basalt", "minecraft:light_blue_wool"}
SCULK = {"minecraft:sculk", "minecraft:sculk_vein", "minecraft:sculk_sensor", "minecraft:sculk_shrieker", "minecraft:sculk_catalyst"}



def skylight(self, x, y, z):
    """Saved sky light. A section saved without a SkyLight array is an all-0 layer (DataLayer.isEmpty():
    data == null && default 0); verified on a live server after reload that such positions read 0."""
    cx, cy, cz = x >> 4, y >> 4, z >> 4
    d = self.sky.get((cx, cy, cz))
    if d is None: return 0, False
    i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
    b = d[i >> 1]; return ((b >> 4) if i & 1 else (b & 15)), True

def biome(self, x, y, z):
    b = self.biomes.get((x >> 4, y >> 4, z >> 4))
    if b is None: return None
    pal, idx = b
    if idx is None: return pal[0]
    return pal[idx[(((y & 15) >> 2) << 4) | (((z & 15) >> 2) << 2) | ((x & 15) >> 2)]]

World.skylight = skylight
World.biome = biome

# ================================================================ v2 checks (26.3, height 2368, bedrock/barrier walls)
AIRS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
NONSOLID = AIRS | {"minecraft:water", "minecraft:lava", None, "minecraft:light", "minecraft:short_grass", "minecraft:fern"}
STRUCT_BLOCKS = re.compile(r"minecraft:(tuff_bricks|tuff_brick_.*|chiseled_tuff.*|polished_tuff.*|.*copper(?!_ore).*|trial_spawner|vault|netherrack|crying_obsidian|gold_block|"
                           r"stone_bricks|mossy_stone_bricks|cracked_stone_bricks|stone_brick_.*|mossy_cobblestone|cobblestone|chain|iron_bars|lantern|oak_planks|spruce_planks|"
                           r"rail|cobweb|.*_terracotta|sandstone|chiseled_sandstone|cut_sandstone|.*_glazed_terracotta|decorated_pot|suspicious_.*|chest|barrel|spawner)$")
PLANTS = re.compile(r"minecraft:(short_grass|fern|poppy|dandelion|cornflower|pink_petals|moss_carpet|azalea|flowering_azalea|sugar_cane|sweet_berry_bush|brown_mushroom|red_mushroom|tall_grass|large_fern)$")

def wall_cols():
    cols = []
    for z in range(-1, 301):
        cols += [(-1, z), (20, z)]
    for x in range(0, 20):
        cols += [(x, -1), (x, 300)]
    return cols

def column(W, x, z, y0=WORLD_MIN_Y, y1=WORLD_MAX_Y):
    return [W.name(x, y, z) for y in range(y0, y1 + 1)]

def main2():
    world_dir, log_path = sys.argv[1], sys.argv[2]
    reg = os.path.join(world_dir, "dimensions/minecraft/overworld/region")
    ent = os.path.join(os.path.dirname(reg), "entities")
    W = World(); W.load(reg); W.load_entities(ent)
    log = open(log_path, errors="replace").read()
    checks = []; extra = {}
    def check(name, ok, details):
        checks.append({"name": name, "passed": bool(ok), "details": details})
        print(("PASS " if ok else "FAIL ") + name + ": " + details, flush=True)
    status = Counter(W.chunks.values())
    secy = [k[1] for k in W.sections]
    print("chunks:", len(W.chunks), dict(status), "sections:", len(W.sections), f"secY {min(secy)}..{max(secy)}", "BEs:", len(W.block_entities),
          "entities:", len(W.entities), "isLightOn:", Counter(W.lighton.values()), "sky layers:", len(W.sky))
    m = re.search(r"holes at (.*)", log)
    holes = [tuple(map(int, h.split())) for h in re.findall(r"\((-?\d+ -?\d+ -?\d+)\)", m.group(1))]
    print("holes:", holes)
    seed_m = None

    # ---- a. floors
    for k in range(8):
        fb, ft = floor_base(k), floor_top(k); hx, _, hz = holes[k]
        expected = {(hx, y, hz): "minecraft:ladder" for y in range(fb, ft + 1)}
        for dx in (-1, 1):
            for dz in (-1, 1): expected[(hx + dx, ft, hz + dz)] = "minecraft:glowstone"
        cnt = Counter(); bad = []; missing = 0
        for x in range(20):
            for z in range(300):
                for y in range(fb, ft + 1):
                    n = W.name(x, y, z)
                    if n is None: missing += 1; continue
                    cnt[n] += 1
                    if n != expected.get((x, y, z), "minecraft:bedrock"): bad.append((x, y, z, n))
        check(f"a.floor{k}", not bad and missing == 0 and cnt["minecraft:ladder"] == 3 and cnt["minecraft:glowstone"] == 4,
              f"y {fb}..{ft} hole ({hx},{hz}) counts {dict(cnt)} missing {missing} bad {bad[:10]}")

    # ---- b. descent
    from_portal = re.search(r"portal", log)
    for k in range(8):
        fb, ft = floor_base(k), floor_top(k); hx, _, hz = holes[k]; lb = k + 1
        lowest = layer_bottom(lb)
        problems = []
        # upper shaft + room
        up = [(y, W.name(hx, y, hz)) for y in range(fb - 5, ft + 1) if W.name(hx, y, hz) != "minecraft:ladder"]
        if up: problems.append(f"upper shaft non-ladder {up}")
        room = Counter(W.name(hx + dx, y, hz + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1) for y in range(fb - 8, fb - 5))
        if set(room) - AIRS: problems.append(f"room {dict(room)}")
        # lower shaft: from fb-9 down to lowest; detect actual bottom of the ladder run
        gaps = []; sup_bad = []; facing_bad = []
        y = fb - 9; run_bottom = None
        for y in range(fb - 9, WORLD_MIN_Y, -1):
            b = W.block(hx, y, hz)
            n = b[0] if b else None
            if y < lowest: break
            if n != "minecraft:ladder":
                gaps.append((y, n))
            else:
                fac = dict(b[1]).get("facing", "north")
                if fac != "north": facing_bad.append((y, fac))
                s = W.name(hx, y, hz + 1)
                if s in NONSOLID or (s and ("leaves" in s or "ladder" in s)): sup_bad.append((y, s))
        # portal-roof case: ladder ended above layer_bottom (8)
        below_end = [(yy, W.name(hx, yy, hz)) for yy in range(lowest - 3, lowest)]
        hall_at_bottom = [W.name(hx, yy, hz) for yy in range(lowest, lowest + 3)]
        # group gaps into ranges
        def ranges(lst):
            out = []
            for yy, n in lst:
                if out and out[-1][1] == yy + 1 and out[-1][2] == n: out[-1][1] = yy
                else: out.append([yy, yy, n])
            return [(a, b, n) for a, b, n in out]
        g = ranges(gaps)
        if g: problems.append(f"{len(gaps)} non-ladder blocks in shaft: {g[:15]}")
        if sup_bad: problems.append(f"{len(sup_bad)} unsupported ladders (z+1): {ranges(sup_bad)[:10]}")
        if facing_bad: problems.append(f"bad facing {facing_bad[:5]}")
        # where is the bottom: next floor hall?
        nxt = (f"next hall y {lowest}..{lowest+5}" if lb < 8 else f"layer8 bottom y {lowest}")
        check(f"b.descent floor{k}->layer{lb}", not problems,
              f"hole ({hx},{hz}) ladder y {fb-9}..{lowest} ({fb-9-lowest+1} blocks) + shaft {fb-5}..{ft}; {nxt}; column at/below bottom {hall_at_bottom}/{below_end}"
              + ("; PROBLEMS: " + " | ".join(problems) if problems else ""))

    # ---- c. walls
    splits = {}; wall_bad = []; wall_badc = Counter()
    for (x, z) in wall_cols():
        col = column(W, x, z)
        if None in col: wall_bad.append((x, z, "missing section")); continue
        # bedrock^a barrier^b
        i = 0
        while i < len(col) and col[i] == "minecraft:bedrock": i += 1
        split = WORLD_MIN_Y + i
        rest = col[i:]
        badr = [(split + j, n) for j, n in enumerate(rest) if n != "minecraft:barrier"]
        splits[(x, z)] = split
        if badr or i == 0:
            wall_bad.append((x, z, split, badr[:5], len(badr)))
            for _, n in badr: wall_badc[n] += 1
    sv = list(splits.values())
    # compare split with adjacent strip column surface (highest non-air non-fluid y)
    def surface(x, z):
        for y in range(WORLD_MAX_Y, -65, -1):
            n = W.name(x, y, z)
            if n and n not in AIRS and n not in ("minecraft:water",) and "leaves" not in n and "log" not in n and n not in ("minecraft:short_grass", "minecraft:tall_grass", "minecraft:light"):
                return y
        return None
    diffs = Counter(); worst = []
    for (x, z), s in splits.items():
        sx, sz = min(max(x, 0), 19), min(max(z, 0), 299)
        su = surface(sx, sz)
        if su is None: continue
        d = s - su; diffs[d] += 1
        worst.append((d, x, z, s, su))
    worst.sort()
    check("c.wall columns bedrock-below-split / barrier-above (all y -2032..335)", not wall_bad,
          f"{len(splits)} wall columns; split y min {min(sv)} max {max(sv)} mean {sum(sv)/len(sv):.1f}; split-minus-adjacent-surface histogram {dict(sorted(diffs.items()))}; "
          f"lowest {worst[:3]} highest {worst[-3:]}; bad {len(wall_bad)} {wall_bad[:10]} types {dict(wall_badc)}")
    outside_bad = Counter(); outside_pos = []; cols = 0
    for (cx, cz) in W.chunks:
        for lx in range(16):
            for lz in range(16):
                x, z = cx * 16 + lx, cz * 16 + lz
                if -1 <= x <= 20 and -1 <= z <= 300: continue
                cols += 1
                for cy in range(MIN_SEC, MAX_SEC + 1):
                    sec = W.sections.get((cx, cy, cz))
                    if sec is None: continue
                    pal, idx = sec
                    if idx is None:
                        if pal[0][0] not in AIRS: outside_bad[pal[0][0]] += 16; outside_pos.append((x, cy * 16, z, pal[0][0]))
                        continue
                    for ly in range(16):
                        n = pal[idx[(ly << 8) | (lz << 4) | lx]][0]
                        if n not in AIRS:
                            outside_bad[n] += 1
                            if len(outside_pos) < 40: outside_pos.append((x, cy * 16 + ly, z, n))
    check("c.outside walls all air", not outside_bad, f"{cols} outside columns; non-air {dict(outside_bad)} e.g. {outside_pos[:20]}")

    # ---- d. skylight
    print("isLightOn per chunk:", Counter(W.lighton.values()))
    for (lo, hi, lab) in [(-100 - 1, -100, None)]: pass
    deep = Counter(); deep_explicit = Counter(); nonzero = []; per_layer = {}
    secs_with = sum(1 for (cx, cy, cz) in W.sky if cy * 16 < -100)
    for l in range(0, 9):
        if l == 0: y0, y1 = -61, -1
        else: y0, y1 = layer_bottom(l), layer_top(l)
        y0 = max(y0, WORLD_MIN_Y); c = Counter(); tot = 0; s = 0; mx = 0; nzp = []
        for x in range(20):
            for z in range(300):
                for y in range(y0, min(y1, -101) + 1) if l > 0 else range(y0, y1 + 1):
                    n = W.name(x, y, z)
                    if n is None or n not in AIRS and n not in ("minecraft:water", "minecraft:light", "minecraft:ladder") and "leaves" not in n: continue
                    v, ex = W.skylight(x, y, z)
                    tot += 1; s += v; mx = max(mx, v)
                    if v > 0 and len(nzp) < 8: nzp.append((x, y, z, v, n, ex))
                    if v > 0: c["nonzero"] += 1
        per_layer[l] = dict(avg=round(s / max(tot, 1), 4), max=mx, nonzero=c["nonzero"], samples=tot, eg=nzp)
    print("skylight per layer (transparent blocks):", json.dumps(per_layer, default=str))
    deepok = all(per_layer[l]["max"] == 0 for l in range(1, 9))
    check("d.skylight 0 in deep layers (y<-100)", deepok,
          f"sky layers saved in {secs_with} sections below y-100; per layer max/avg/nonzero: " +
          "; ".join(f"L{l}: max {v['max']} avg {v['avg']} nz {v['nonzero']}/{v['samples']}" + (f" eg {v['eg'][:3]}" if v['nonzero'] else "") for l, v in per_layer.items()))
    extra["skylight"] = per_layer

    # ---- e. biomes
    surf = Counter()
    for x in range(0, 20, 4):
        for z in range(0, 300, 4):
            for y in range(48, 97, 4): surf[W.biome(x, y, z)] += 1
    zb = []
    for z in range(0, 300, 4):
        b = W.biome(8, 64, z)
        if not zb or zb[-1][1] != b: zb.append((z, b))
    deepb = {}
    for l in range(1, 9):
        c = Counter()
        for x in range(0, 20, 4):
            for z in range(0, 300, 4):
                for y in range(layer_bottom(l), layer_top(l) + 1, 4): c[W.biome(x, y, z)] += 1
        deepb[l] = dict(c)
    l0 = Counter(W.biome(x, y, z) for x in range(0, 20, 4) for z in range(0, 300, 4) for y in range(-60, 0, 4))
    check("e.biome variety", len(surf) > 1 and all(len(v) >= 1 for v in deepb.values()),
          f"surface y48..96 {len(surf)} biomes {dict(surf)}; along z at y64: {zb}; layer0 y-60..0 {dict(l0)}; deep {deepb}")

    # ---- f. trees per layer + lights + items
    ent_by_layer = defaultdict(Counter); items_by_layer = defaultdict(Counter); ent_out = []
    for e in W.entities:
        pos = e.get("Pos")
        if not pos: continue
        x, y, z = pos; l = layer_at(int(math.floor(y)))
        ent_by_layer[l][e.get("id")] += 1
        if e.get("id") == "minecraft:item":
            it = e.get("Item", {}); items_by_layer[l][it.get("id")] += it.get("count", it.get("Count", 1))
        if not (0 <= x < 20 and 0 <= z < 300): ent_out.append((e.get("id"), round(x, 1), round(y, 1), round(z, 1)))
    layer_stats = {}
    for l in range(1, 9):
        cnt, miss = W.box_counts(0, 19, layer_bottom(l), layer_top(l), 0, 299)
        logs = {k: v for k, v in cnt.items() if LOGS.match(k)}; leaves = {k: v for k, v in cnt.items() if LEAVES.match(k)}
        plants = {k.replace("minecraft:", ""): v for k, v in cnt.items() if PLANTS.match(k)}
        layer_stats[l] = dict(logs=logs, leaves=sum(leaves.values()), leaf_types=leaves, light=cnt["minecraft:light"], plants=plants,
                              items=dict(items_by_layer.get(l, {})), entities=dict(ent_by_layer.get(l, {})))
        nitems = sum(items_by_layer.get(l, {}).values())
        check(f"f.layer{l} trees/lights/items", sum(logs.values()) > 0 and sum(leaves.values()) > 0 and cnt["minecraft:light"] > 0 and nitems == 0,
              f"logs {logs} leaves {leaves} light {cnt['minecraft:light']} plants {plants} items {dict(items_by_layer.get(l, {}))} mobs {dict(ent_by_layer.get(l, {}))}")
    print("entities layer 0 / -1:", dict(ent_by_layer.get(0, {})), dict(ent_by_layer.get(-1, {})), "items layer0:", dict(items_by_layer.get(0, {})))
    check("f.no entities outside strip", not ent_out, f"{ent_out[:10]}")
    extra["layers"] = layer_stats

    # ---- g. ancient city
    b5, t5 = layer_bottom(5), layer_top(5)
    cityb = W.find(CITY - {"minecraft:chest"}, -100, 120, b5, t5, -50, 350)
    bt = Counter(c[3][0] for c in cityb)
    out = [c[:4] for c in cityb if not (0 <= c[0] <= 19 and 0 <= c[2] <= 299)]
    at20 = Counter(W.name(20, y, z) for y in range(b5, t5 + 1) for z in range(0, 300))
    maxrun = (0, None); maxrun_city = (0, None); runs = Counter()
    fs = forest_floor(5)
    for x in range(20):
        for z in range(300):
            r = 0; rc = 0
            for y in range(b5, t5 + 1):
                n = W.name(x, y, z)
                if n in AIRS:
                    r += 1
                    if r > maxrun[0]: maxrun = (r, (x, y - r + 1, y, z))
                else: r = 0
                if y < fs - 12:
                    if n in AIRS:
                        rc += 1
                        if rc > maxrun_city[0]: maxrun_city = (rc, (x, y - rc + 1, y, z))
                    else: rc = 0
    if cityb:
        ys = [c[1] for c in cityb]; zs = [c[2] for c in cityb]
    check("g.ancient city in layer 5", bt["minecraft:reinforced_deepslate"] > 0 and not out and len(cityb) > 500,
          f"{len(cityb)} city blocks" + (f" y {min(ys)}..{max(ys)} z {min(zs)}..{max(zs)}" if cityb else "") + f"; types {dict(bt.most_common(10))}; outside strip {len(out)} {out[:5]}; "
          f"x=20 column contents in layer5 {dict(at20)}; max vertical air run in layer5 strip {maxrun}; below forest (y<{fs-12}) {maxrun_city}")

    # ---- h. structures low
    low = W.find({n for n in set(p[0] for pal, _ in W.sections.values() for p in pal) if STRUCT_BLOCKS.match(n)}, -1, 20, WORLD_MIN_Y, -55, -1, 300)
    lowc = defaultdict(list)
    for x, y, z, b in low: lowc[(b[0], layer_at(y))].append((x, y, z))
    pz = None
    fr = W.find({"minecraft:end_portal_frame"}, 0, 19, WORLD_MIN_Y, -1800, 0, 299)
    if fr: pz = (min(f[2] for f in fr) + max(f[2] for f in fr)) / 2
    def in_room(x, y, z): return pz is not None and 1 <= x <= 18 and pz - 10 <= z <= pz + 9 and y <= -2017
    lowx = {k: (len(v), v[:3]) for k, v in lowc.items()}
    nonroom = [(k, len([p for p in v if not in_room(*p)]), [p for p in v if not in_room(*p)][:3]) for k, v in lowc.items()]
    nonroom = [n for n in nonroom if n[1]]
    hall0 = W.box_counts(0, 19, -61, -55, 0, 299)[0]
    check("h.no vanilla structure blocks below y-55 (outside portal room)", not nonroom,
          f"structure-type blocks y<=-55 by (block, layer): {lowx}; outside portal room: {nonroom[:12]}; layer0 hall y-61..-55 composition {dict(hall0)}")

    # ---- i. fluids in seal band
    fl = W.find({"minecraft:water", "minecraft:lava"}, 0, 19, -55, -53, 0, 299)
    src = [f[:3] + (f[3][0],) for f in fl if dict(f[3][1]).get("level", "0") == "0"]
    wl = W.find({n for n in set(p[0] for pal, _ in W.sections.values() for p in pal)}, 0, 19, -55, -53, 0, 299)
    wlog = [w[:4] for w in wl if dict(w[3][1]).get("waterlogged") == "true"]
    fl2 = W.find({"minecraft:water", "minecraft:lava"}, 0, 19, -61, -56, 0, 299)
    check("i.no fluids y-55..-53 (seal) ", not fl, f"fluid blocks {len(fl)} (sources {len(src)}) e.g. {[f[:3]+(f[3],) for f in fl[:8]]}; waterlogged {len(wlog)}; fluids in hall y-61..-56: {len(fl2)} e.g. {[f[:3]+(f[3],) for f in fl2[:6]]}")

    # ---- j. spawn + starter chest
    lvl = read_nbt(gzip.decompress(open(os.path.join(world_dir, "level.dat"), "rb").read()))
    data = lvl.get("Data", lvl); sp = data.get("spawn")
    ms = re.search(r"world spawn moved to BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}", log)
    mc = re.search(r"starter chest placed at BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}", log)
    pos = tuple(sp["pos"]) if sp and "pos" in sp else tuple(map(int, ms.groups())) if ms else None
    x, y, z = pos
    below, feet, head = W.name(x, y - 1, z), W.name(x, y, z), W.name(x, y + 1, z)
    sp_ok = 0 <= x <= 19 and 0 <= z <= 299 and below not in NONSOLID and feet in AIRS
    chest = None; items = None
    if mc:
        cp = tuple(map(int, mc.groups())); be = W.block_entities.get(cp)
        chest = (cp, W.block(*cp), W.name(cp[0], cp[1] - 1, cp[2]))
        items = [(i.get("id"), i.get("count", i.get("Count"))) for i in (be or {}).get("Items", [])]
    marker = os.path.exists(os.path.join(world_dir, "antcraft_starter_chest"))
    ids = {i[0] for i in items or []}
    ch_ok = bool(items) and {"minecraft:oak_log", "minecraft:oak_sapling", "minecraft:bread"} <= ids and marker
    area = Counter(W.name(x + dx, y - 1, z + dz) for dx in range(-2, 3) for dz in range(-2, 3))
    check("j.spawn inside strip on solid ground", sp_ok, f"spawn {pos} (level.dat {sp}); below {below} feet {feet} head {head}; 5x5 below {dict(area)}; log moved {ms.groups() if ms else None}")
    check("j.starter chest", ch_ok, f"chest {chest}; items {items}; marker file {marker}")

    json.dump({"checks": checks, **extra}, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "results2.json"), "w"), indent=1, default=str)

if __name__ == "__main__" and "--v2" in sys.argv:
    main2()
