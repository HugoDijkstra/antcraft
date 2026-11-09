package dev.antcraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.NetherFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

/**
 * The tall Nether below the moved vanilla band (Nether layout 3, spec v2 4.1/4.2): the holed
 * bedrock floors NF0..NF18 and the 19 deep layers between them. Each layer, bottom up: the floor,
 * a 6-tall causeway hall along the strip (closed nether-brick ceiling), a 3-thick crust, a lava sea
 * up to bottom + 17, and simplex caverns shaped by the layer's {@link NetherRow.Shape}.
 *
 * <p>Ways down: under each hole a weeping-vine column hangs from the floor's bedrock down onto the
 * roof of a nether-brick stairwell tower, whose spiral stairs lead down through the lava sea into
 * the hall; the hall leads to the next hole. More towers stand about every 75 blocks.
 *
 * <p>Everything is a pure function of (seed, strip bounds, x, y, z), like {@link DeepTerrain}.
 */
public final class DeepNetherTerrain {
	private record Key(long seed, StripBounds bounds, NetherLayout layout) {}

	private static final Map<Key, DeepNetherTerrain> CACHE = new ConcurrentHashMap<>();

	public static DeepNetherTerrain forSeed(long seed, StripBounds bounds, NetherLayout layout) {
		return CACHE.computeIfAbsent(new Key(seed, bounds, layout), k -> new DeepNetherTerrain(k.seed(), k.bounds(), k.layout()));
	}

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
	static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
	static final BlockState BRICKS = Blocks.NETHER_BRICKS.defaultBlockState();
	static final BlockState GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState();
	static final BlockState SHROOMLIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
	static final BlockState VINE_BODY = Blocks.WEEPING_VINES_PLANT.defaultBlockState();
	static final BlockState VINE_TIP = Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, GrowingPlantHeadBlock.MAX_AGE);

	/** A stairwell tower: centre, hall bottom (first air row), roof y, and whether it gets an island at the lava line. */
	public record Tower(int x, int z, int bottom, int roof, int lavaTop, boolean island) {}

	/** A hand-built place: its box, a block function and the block entities to fill at decoration. */
	private record Build(int layer, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Shape shape, List<Entity> entities) {
		boolean intersects(int x0, int z0, int x1, int z1) {
			return this.maxX >= x0 && this.minX <= x1 && this.maxZ >= z0 && this.minZ <= z1;
		}
	}

	@FunctionalInterface
	private interface Shape {
		/** The block at (x, y, z) inside the build's box, or null to keep the terrain. */
		@Nullable BlockState at(int x, int y, int z);
	}

	/** A chest (loot table or fixed items) or a spawner of a build. */
	private record Entity(BlockPos pos, @Nullable ResourceKey<LootTable> loot, @Nullable List<ItemStack> items, @Nullable EntityType<?> spawner) {}

	private final long seed;
	private final StripBounds bounds;
	private final NetherLayout layout;
	private final NetherRow[] rows;
	private final int[] holeX, holeZ;
	private final Tower[][] towers;
	private final BlockState[][] layerStones;
	private final List<Build> builds = new ArrayList<>();
	private final SimplexNoise cavern, detail, noodleA, noodleB, patch, islet, terrace, spike;

	private DeepNetherTerrain(long seed, StripBounds bounds, NetherLayout layout) {
		this.seed = seed;
		this.bounds = bounds;
		this.layout = layout;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 4242));
		this.cavern = new SimplexNoise(random);
		this.detail = new SimplexNoise(random);
		this.noodleA = new SimplexNoise(random);
		this.noodleB = new SimplexNoise(random);
		this.patch = new SimplexNoise(random);
		this.islet = new SimplexNoise(random);
		this.terrace = new SimplexNoise(random);
		this.spike = new SimplexNoise(random);
		int n = layout.deepLayers;
		this.rows = new NetherRow[n + 1];
		this.layerStones = new BlockState[n + 1][];
		for (int l = 1; l <= n; l++) {
			this.rows[l] = layout.row(l);
			NetherRow row = this.rows[l];
			List<BlockState> stones = new ArrayList<>();
			for (BlockState s : new BlockState[] {row.base(), row.patch()}) {
				if (s.is(Blocks.GLOWSTONE) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.CRIMSON_NYLIUM) || s.is(Blocks.WARPED_NYLIUM) || s.is(Blocks.NETHER_WART_BLOCK)
					|| s.is(Blocks.WARPED_WART_BLOCK) || s.is(Blocks.SMOOTH_QUARTZ) || s.is(Blocks.CRYING_OBSIDIAN) || s.is(Blocks.MAGMA_BLOCK)) continue;
				stones.add(s);
			}
			this.layerStones[l] = stones.toArray(BlockState[]::new);
		}
		this.holeX = new int[layout.floorCount];
		this.holeZ = new int[layout.floorCount];
		for (int k = 0; k < layout.floorCount; k++) {
			this.holeX[k] = layout.holeX(seed, k, bounds);
			this.holeZ[k] = layout.holeZ(seed, k, bounds);
		}
		this.towers = new Tower[n + 1][];
		for (int l = 1; l <= n; l++) {
			int bottom = layout.layerBottom(l), lavaTop = layout.lavaTop(l);
			int roof = bottom + Math.max(NetherLayout.TOWER_ROOF, layout.row(l).lavaHeight() + 4);
			List<Tower> list = new ArrayList<>();
			for (int[] t : layout.towers(seed, l, bounds)) list.add(new Tower(t[0], t[1], bottom, roof, lavaTop, true));
			this.towers[l] = list.toArray(Tower[]::new);
		}
		this.planBuilds();
		// the anchor shrine and the forge stand on their own towers (no island: the build is the platform)
		List<Tower> five = new ArrayList<>(List.of(this.towers[5]));
		BlockPos anchor = layout.anchorShrine(seed, bounds);
		five.add(new Tower(anchor.getX(), anchor.getZ() - 3, layout.layerBottom(5), anchor.getY() - 1, layout.lavaTop(5), false));
		this.towers[5] = five.toArray(Tower[]::new);
		List<Tower> last = new ArrayList<>(List.of(this.towers[n]));
		BlockPos forge = layout.emberForge(seed, bounds);
		last.add(new Tower(forge.getX(), forge.getZ() - 3, layout.layerBottom(n), forge.getY() - 1, layout.lavaTop(n), false));
		this.towers[n] = last.toArray(Tower[]::new);
	}

	public NetherLayout layout() {
		return this.layout;
	}

	public long seed() {
		return this.seed;
	}

	public StripBounds bounds() {
		return this.bounds;
	}

	public int holeX(int k) {
		return this.holeX[k];
	}

	public int holeZ(int k) {
		return this.holeZ[k];
	}

	public Tower[] towers(int layer) {
		return this.towers[layer].clone();
	}

	/** Vine column of a layer: under the hole above it, hanging from that floor's bedrock (x, z). */
	public int[] vineColumn(int layer) {
		return new int[] {this.holeX[layer - 1], this.holeZ[layer - 1] + 1};
	}

	// ------------------------------------------------------------------ raw chunk access

	/** Raw section write (no fluid ticks: the lava seas are level and need none). */
	static void set(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
		section.setBlockState(lx, y & 15, lz, state, false);
	}

	static BlockState get(ChunkAccess chunk, int lx, int y, int lz) {
		return chunk.getSection(chunk.getSectionIndex(y)).getBlockState(lx, y & 15, lz);
	}

	private void setIfInChunk(ChunkAccess chunk, int x, int y, int z, BlockState state) {
		ChunkPos pos = chunk.getPos();
		if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ() || !this.bounds.inside(x, z)) return;
		set(chunk, x - pos.getMinBlockX(), y, z - pos.getMinBlockZ(), state);
	}

	double hash01(int x, int y, int z, int salt) {
		long h = Strip.mix(this.seed ^ ((long) x * 341873128712L + (long) z * 132897987541L + (long) y * 1664525L), salt);
		return (h >>> 11) * 0x1.0p-53;
	}

	// ------------------------------------------------------------------ cavern shape

	/** True if the cavern noise opens (x, y, z) in a layer (above the crust). Pure. */
	public boolean carved(int layer, int x, int y, int z) {
		NetherRow row = this.rows[layer];
		int bottom = this.layout.layerBottom(layer), top = this.layout.layerTop(layer);
		if (y <= bottom + 8 || y > top) return false;
		double l = layer * 97.0;
		return switch (row.shape()) {
			case OPEN -> this.open(x, y, z, l, 0.018, row.threshold());
			case MAZE -> this.noodle(x, y, z, l, 0.09);
			case SHAFTS -> this.cavern.get(x * 0.035 + l, y * 0.004, z * 0.035) * 0.8 + this.detail.get(x * 0.08, y * 0.02 + l, z * 0.08) * 0.2 > row.threshold()
				|| this.noodle(x, y, z, l, 0.06);
			case ISLANDS -> {
				double v = this.islet.get(x * 0.045 + l, y * 0.07, z * 0.045) * 0.85 + this.detail.get(x * 0.12, y * 0.12 + l, z * 0.12) * 0.15;
				int lava = this.layout.lavaTop(layer);
				if (row.lavaHeight() > 17) v += Math.max(0.0, 1.0 - Math.abs(y - lava - 2) / 6.0) * 0.35; // islands at the lake's surface
				yield v < row.threshold() && y < top - 3;
			}
			case STEPPED -> {
				int floor = this.terraceFloor(layer, x, z);
				// the terraces stand in a lava sea like every layer's floor (caverns below the lowest terrace)
				yield y > floor ? y <= floor + 9 || this.open(x, y, z, l, 0.018, row.threshold()) : y <= this.layout.lavaTop(layer) && this.open(x, y, z, l, 0.018, row.threshold() - 0.1);
			}
			case MIXED -> this.open(x, y, z, l, 0.03, row.threshold()) || this.noodle(x, y, z, l, 0.08);
			case CHASM -> this.open(x, y, z, l, 0.012, row.threshold() - 0.35) && y < top - 4;
		};
	}

	private boolean open(int x, int y, int z, double l, double scale, double threshold) {
		double c = this.cavern.get(x * scale + l, y * scale / 3.5, z * scale - l) * 0.8 + this.detail.get(x * 0.06, y * 0.06 / 2.0 + l, z * 0.06) * 0.2;
		return c > threshold;
	}

	private boolean noodle(int x, int y, int z, double l, double width) {
		double a = this.noodleA.get(x * 0.035 + l, y * 0.05, z * 0.035);
		if (Math.abs(a) > width) return false;
		return Math.abs(this.noodleB.get(x * 0.035, y * 0.05 + l, z * 0.035)) < width;
	}

	/** Wart Terraces: the terrace floor of a column (steps of 6). */
	int terraceFloor(int layer, int x, int z) {
		double t = (this.terrace.get(x * 0.02 + layer, z * 0.02) + 1.0) * 0.5; // 0..1
		return this.layout.layerBottom(layer) + 18 + 6 * Math.min(7, (int) (t * 8));
	}

	private BlockState stone(int layer, int x, int y, int z) {
		NetherRow row = this.rows[layer];
		if (layer == this.layout.deepLayers && this.hash01(x, y, z, 9900) < 0.03) return Blocks.CRYING_OBSIDIAN.defaultBlockState();
		if (layer == 11 && this.hash01(x, y, z, 9901) < 0.01) return Blocks.GOLD_BLOCK.defaultBlockState();
		if (row.patchThreshold() <= 0.0) return row.base();
		double p = this.patch.get(x * 0.07 + layer * 31.0, y * 0.07, z * 0.07);
		return p > row.patchThreshold() ? row.patch() : row.base();
	}

	/** A floor y in a column (solid below, 3 free above), scanning up from {@code from}, or Integer.MIN_VALUE. */
	public int floorAbove(int layer, int x, int z, int from, int to) {
		for (int y = from; y <= to; y++) {
			if (!this.carved(layer, x, y - 1, z) && this.carved(layer, x, y, z) && this.carved(layer, x, y + 1, z) && this.carved(layer, x, y + 2, z)) return y;
		}
		return Integer.MIN_VALUE;
	}

	// ------------------------------------------------------------------ terrain

	/** NF0 out of the vanilla bedrock floor, and the closed tunnel hall above it (1907..1912, ceiling 1913..1914). */
	public void reshapeBand(ChunkAccess chunk, int lx, int lz, int x, int z) {
		int floorBase = this.layout.floorBase(0), floorTop = this.layout.floorTop(0);
		for (int y = floorBase; y <= floorTop; y++) set(chunk, lx, y, lz, BEDROCK);
		int hall = this.layout.hallBottom(0);
		for (int y = hall; y < hall + this.layout.hallHeight; y++) set(chunk, lx, y, lz, AIR);
		boolean light = Math.floorMod(z - this.bounds.minZ(), 10) == 5 && (x == this.bounds.centerX() || x == this.bounds.minX() + 2 || x == this.bounds.maxX() - 2);
		set(chunk, lx, hall + this.layout.hallHeight, lz, light ? SHROOMLIGHT : BRICKS);
		set(chunk, lx, hall + this.layout.hallHeight + 1, lz, BRICKS);
	}

	/** Fills one strip column below the band: bottom bedrock, floors NF1..NF18 and every deep layer. */
	public void fillColumn(ChunkAccess chunk, int lx, int lz, int x, int z) {
		for (int y = this.layout.worldMinY; y <= this.layout.bottomTopY; y++) set(chunk, lx, y, lz, BEDROCK);
		for (int k = 1; k < this.layout.floorCount; k++) {
			for (int y = this.layout.floorBase(k); y <= this.layout.floorTop(k); y++) set(chunk, lx, y, lz, BEDROCK);
		}
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) this.fillLayer(chunk, lx, lz, x, z, layer);
	}

	private void fillLayer(ChunkAccess chunk, int lx, int lz, int x, int z, int layer) {
		NetherRow row = this.rows[layer];
		int bottom = this.layout.layerBottom(layer), top = this.layout.layerTop(layer);
		int lava = this.layout.lavaTop(layer);
		// the causeway hall: 6 air rows, a nether-brick ceiling with lights, the crust above it
		for (int y = bottom; y < bottom + 6; y++) set(chunk, lx, y, lz, AIR);
		boolean light = Math.floorMod(z - this.bounds.minZ() + layer * 3, 10) == 5 && (x == this.bounds.centerX() || x == this.bounds.minX() + 2 || x == this.bounds.maxX() - 2);
		set(chunk, lx, bottom + 6, lz, light ? SHROOMLIGHT : BRICKS);
		set(chunk, lx, bottom + 7, lz, row.crust());
		set(chunk, lx, bottom + 8, lz, row.crust());
		// caverns, lava sea, stone
		boolean pillar = layer == 15 && this.quartzPillar(x, z);
		boolean below = false; // was y-1 open?
		int stalactite = layer == 6 ? this.stalactiteLength(x, z) : 0;
		for (int y = bottom + 9; y <= top; y++) {
			boolean open = this.carved(layer, x, y, z);
			BlockState state;
			if (open) {
				state = y <= lava ? LAVA : AIR;
				if (pillar && y > lava) state = Blocks.QUARTZ_PILLAR.defaultBlockState();
			} else {
				state = this.stone(layer, x, y, z);
				if (row.ground() != null && y >= lava && this.carved(layer, x, y + 1, z) && y + 1 > lava) state = row.ground();
			}
			set(chunk, lx, y, lz, state);
			if (!open && below && y - 1 > lava + 1) {
				// a ceiling over open air: lights, glowstone stalactites, lava falls
				if (stalactite > 0) {
					for (int d = 1; d <= stalactite && y - d > lava + 1; d++) {
						if (!this.carved(layer, x, y - d, z)) break;
						set(chunk, lx, y - d, lz, GLOWSTONE);
					}
				} else if (row.lightRate() > 0 && this.hash01(x, y, z, 9700 + layer) < row.lightRate()) {
					set(chunk, lx, y, lz, row.light().is(Blocks.LANTERN) || row.light().is(Blocks.SOUL_LANTERN) ? SHROOMLIGHT : row.light());
				} else if (layer == 18 && this.hash01(x, y, z, 9800) < 0.004 && !this.nearPath(layer, x, z, 6)) {
					set(chunk, lx, y, lz, LAVA);
					if (chunk instanceof net.minecraft.world.level.chunk.ProtoChunk) {
						chunk.markPosForPostProcessing(new BlockPos(x, y, z)); // a lava fall: let it pour once the chunk loads
					}
				}
			}
			if (open && y > lava && !below && layer == 8 && get(chunk, lx, y - 1, lz).is(Blocks.SOUL_SAND) && this.hash01(x, y, z, 9810) < 0.35) {
				set(chunk, lx, y, lz, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3));
			}
			below = open;
		}
		if (layer == 17) this.chasmBridge(chunk, lx, lz, x, z, layer);
	}

	/** Quartz Halls: crystal pillars floor to ceiling on a jittered grid. */
	private boolean quartzPillar(int x, int z) {
		int cx = Math.floorDiv(x - this.bounds.minX(), 9), cz = Math.floorDiv(z - this.bounds.minZ(), 9);
		int px = this.bounds.minX() + cx * 9 + 2 + (int) (this.hash01(cx, 15, cz, 9820) * 5), pz = this.bounds.minZ() + cz * 9 + 2 + (int) (this.hash01(cx, 15, cz, 9821) * 5);
		return Math.abs(x - px) + Math.abs(z - pz) <= 1 && this.hash01(cx, 15, cz, 9822) < 0.6 && !this.nearPath(15, x, z, 4);
	}

	/** Glowstone Grotto: length of the glowstone stalactite hanging in this column (0 = none). */
	private int stalactiteLength(int x, int z) {
		int cx = Math.floorDiv(x - this.bounds.minX(), 7), cz = Math.floorDiv(z - this.bounds.minZ(), 7);
		int px = this.bounds.minX() + cx * 7 + 1 + (int) (this.hash01(cx, 6, cz, 9830) * 5), pz = this.bounds.minZ() + cz * 7 + 1 + (int) (this.hash01(cx, 6, cz, 9831) * 5);
		double d = Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
		int length = 4 + (int) (this.hash01(cx, 6, cz, 9832) * 14);
		return d > 2.5 ? 0 : (int) (length * (1.0 - d / 3.0));
	}

	/** Soulfire Chasm: basalt bridges across the strip every 24 blocks, soul lanterns hanging under them. */
	private void chasmBridge(ChunkAccess chunk, int lx, int lz, int x, int z, int layer) {
		int off = Math.floorMod(z - this.bounds.minZ() - 9, 24);
		if (off > 1) return;
		int y = this.layout.layerBottom(layer) + 40 + (int) (this.hash01(0, layer, Math.floorDiv(z - this.bounds.minZ() - 9, 24), 9840) * 30);
		for (int dy = 0; dy <= 3; dy++) if (!get(chunk, lx, y + dy, lz).isAir()) return;
		set(chunk, lx, y, lz, Blocks.POLISHED_BASALT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
		if (off == 0 && Math.floorMod(x - this.bounds.minX(), 5) == 2 && get(chunk, lx, y - 1, lz).isAir()) {
			set(chunk, lx, y - 1, lz, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		}
	}

	/** True near a vine column, a tower or a hole of the layer (lava falls and pillars keep away). */
	boolean nearPath(int layer, int x, int z, int radius) {
		int[] vine = this.vineColumn(layer);
		if (Math.abs(x - vine[0]) <= radius && Math.abs(z - vine[1]) <= radius) return true;
		for (Tower t : this.towers[layer]) if (Math.abs(x - t.x()) <= radius + 4 && Math.abs(z - t.z()) <= radius + 4) return true;
		if (layer < this.layout.deepLayers && Math.abs(x - this.holeX[layer]) <= radius && Math.abs(z - this.holeZ[layer]) <= radius) return true;
		return false;
	}

	// ------------------------------------------------------------------ towers, vines, holes

	/** Everything that makes the way down, in this chunk: builds, towers, vine columns, landing rooms, holes, boss rooms. */
	public void cutPaths(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		for (Build build : this.builds) {
			if (!build.intersects(pos.getMinBlockX(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockZ())) continue;
			for (int x = Math.max(build.minX(), pos.getMinBlockX()); x <= Math.min(build.maxX(), pos.getMaxBlockX()); x++) {
				for (int z = Math.max(build.minZ(), pos.getMinBlockZ()); z <= Math.min(build.maxZ(), pos.getMaxBlockZ()); z++) {
					if (!this.bounds.inside(x, z)) continue;
					for (int y = build.minY(); y <= build.maxY(); y++) {
						BlockState state = build.shape().at(x, y, z);
						if (state != null) set(chunk, x - pos.getMinBlockX(), y, z - pos.getMinBlockZ(), state);
					}
				}
			}
		}
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) {
			for (Tower t : this.towers[layer]) {
				if (t.x() + 4 < pos.getMinBlockX() || t.x() - 4 > pos.getMaxBlockX() || t.z() + 4 < pos.getMinBlockZ() || t.z() - 4 > pos.getMaxBlockZ()) continue;
				for (int dx = -4; dx <= 4; dx++) {
					for (int dz = -4; dz <= 4; dz++) {
						int x = t.x() + dx, z = t.z() + dz;
						if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ() || !this.bounds.inside(x, z)) continue;
						for (int y = t.bottom(); y <= t.roof() + 3; y++) {
							BlockState state = towerBlock(t, dx, y, dz, this.rows[layer].base());
							if (state != null) set(chunk, x - pos.getMinBlockX(), y, z - pos.getMinBlockZ(), state);
						}
					}
				}
			}
			this.cutVine(chunk, layer);
		}
		for (int k = 0; k < this.layout.floorCount; k++) this.cutHole(chunk, k);
	}

	/** Ring of the spiral stairs around the tower's central pillar, in walking order. */
	private static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};

	/**
	 * A tower's block at offset (dx, dz) from its centre and y, or null to keep what is there. The
	 * central pillar, the spiral steps (one per block of height, from the hall floor up to just
	 * under the roof), walls from the hall ceiling up, a roof with one opening over the top step,
	 * and (island towers) a platform at the lava line with air above.
	 */
	static @Nullable BlockState towerBlock(Tower t, int dx, int y, int dz, BlockState platform) {
		int adx = Math.abs(dx), adz = Math.abs(dz);
		if (adx <= 2 && adz <= 2) {
			if (y > t.roof()) return AIR;
			if (adx == 0 && adz == 0) return BRICKS;
			if (adx <= 1 && adz <= 1) {
				int ring = ringIndex(dx, dz);
				int top = t.roof() - 1 - t.bottom(); // index of the top step
				if (y == t.roof()) return Math.floorMod(top, 8) == ring ? AIR : BRICKS;
				return Math.floorMod(y - t.bottom(), 8) == ring ? BRICKS : AIR;
			}
			if (y < t.bottom() + 6) return null; // the hall: an open spiral
			return BRICKS;
		}
		if (!t.island() || adx > 4 || adz > 4) return null;
		if (y == t.lavaTop()) return platform;
		if (y > t.lavaTop() && y <= t.roof() + 3) return AIR;
		return null;
	}

	private static int ringIndex(int dx, int dz) {
		for (int i = 0; i < RING.length; i++) if (RING[i][0] == dx && RING[i][1] == dz) return i;
		return -1;
	}

	/** The weeping-vine column of a layer, from under the floor above down onto the tower roof. */
	private void cutVine(ChunkAccess chunk, int layer) {
		int[] v = this.vineColumn(layer);
		ChunkPos pos = chunk.getPos();
		if (v[0] < pos.getMinBlockX() || v[0] > pos.getMaxBlockX() || v[1] < pos.getMinBlockZ() || v[1] > pos.getMaxBlockZ()) return;
		Tower tower = this.towers[layer][0];
		int from = this.layout.floorBase(layer - 1) - 1, to = tower.roof() + 1;
		for (int y = from; y >= to; y--) setIfInChunk(chunk, v[0], y, v[1], y == to ? VINE_TIP : VINE_BODY);
	}

	/** One floor's hole: ladder, markers, the landing room under it, hall lights, a guardian seal and boss room. */
	private void cutHole(ChunkAccess chunk, int k) {
		int hx = this.holeX[k], hz = this.holeZ[k];
		ChunkPos pos = chunk.getPos();
		if (hz + ArenaManager.WALL_OFFSET < pos.getMinBlockZ() || hz - ArenaManager.WALL_OFFSET > pos.getMaxBlockZ()) return;
		int floorBase = this.layout.floorBase(k), floorTop = this.layout.floorTop(k);
		for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) setIfInChunk(chunk, hx + dx, floorTop, hz + dz, GLOWSTONE);
		// landing room under the floor (the vine column runs through its south middle)
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 1) continue;
				for (int y = floorBase - 3; y <= floorBase - 1; y++) setIfInChunk(chunk, hx + dx, y, hz + dz, AIR);
				setIfInChunk(chunk, hx + dx, floorBase - 4, hz + dz, dx == 1 && dz == -1 ? GLOWSTONE : BRICKS);
			}
		}
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
		for (int y = floorBase; y <= floorTop; y++) setIfInChunk(chunk, hx, y, hz, ladder);
		int ceiling = floorTop + 1 + this.layout.hallHeight;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) setIfInChunk(chunk, hx + dx, ceiling, hz + dz, GLOWSTONE);
		for (int dx = -2; dx <= 2; dx += 4) for (int dz = -2; dz <= 2; dz += 4) setIfInChunk(chunk, hx + dx, floorTop + 1, hz + dz, Blocks.LANTERN.defaultBlockState());
		if (this.layout.isGuardianFloor(k) && AntCraftConfig.get().guardians) {
			setIfInChunk(chunk, hx, floorTop, hz, ModContent.GUARDIAN_SEAL.defaultBlockState());
			this.buildArena(chunk, k, hz);
		}
		for (int y = floorTop + 1; y <= floorTop + 3; y++) setIfInChunk(chunk, hx, y, hz, AIR);
	}

	/** The boss room in the hall above guardian floor {@code k}: as in the overworld ({@link DeepTerrain}). */
	private void buildArena(ChunkAccess chunk, int k, int hz) {
		ChunkPos pos = chunk.getPos();
		if (hz + ArenaManager.WALL_OFFSET < pos.getMinBlockZ() || hz - ArenaManager.WALL_OFFSET > pos.getMaxBlockZ()) return;
		int floorTop = this.layout.floorTop(k);
		int top = floorTop + this.layout.hallHeight + ArenaManager.WALL_EXTRA_HEIGHT;
		BlockState wall = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
		for (int x = this.bounds.minX(); x <= this.bounds.maxX(); x++) {
			for (int side = -1; side <= 1; side += 2) {
				int z = hz + side * ArenaManager.WALL_OFFSET;
				for (int y = floorTop + 1; y <= top; y++) {
					boolean door = ArenaManager.isDoorway(this.layout, this.bounds, k, x, y);
					setIfInChunk(chunk, x, y, z, door ? AIR : wall);
					if (door) {
						setIfInChunk(chunk, x, y, z - 1, AIR);
						setIfInChunk(chunk, x, y, z + 1, AIR);
					}
				}
			}
		}
	}

	/**
	 * Puts the way down back after structures were placed over it (the deep fortress, the bastion,
	 * ruined portals and fossils can reach a tower or a vine column): the towers and vines in the
	 * 3x3 region around {@code center}. Idempotent.
	 */
	public void repairPaths(WorldGenLevel level, ChunkPos center) {
		int x0 = (center.x() - 1) << 4, x1 = ((center.x() + 1) << 4) + 15, z0 = (center.z() - 1) << 4, z1 = ((center.z() + 1) << 4) + 15;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int layer : new int[] {1, 4, 9, 12, 16}) {
			for (Tower t : this.towers[layer]) {
				for (int dx = -2; dx <= 2; dx++) {
					for (int dz = -2; dz <= 2; dz++) {
						int x = t.x() + dx, z = t.z() + dz;
						if (x < x0 || x > x1 || z < z0 || z > z1 || !this.bounds.inside(x, z)) continue;
						for (int y = t.bottom(); y <= t.roof() + 2; y++) {
							BlockState want = towerBlock(t, dx, y, dz, this.rows[layer].base());
							if (want != null && level.getBlockState(at.set(x, y, z)) != want) level.setBlock(at, want, Block.UPDATE_CLIENTS);
						}
					}
				}
			}
			int[] v = this.vineColumn(layer);
			if (v[0] < x0 || v[0] > x1 || v[1] < z0 || v[1] > z1) continue;
			int from = this.layout.floorBase(layer - 1) - 1, to = this.towers[layer][0].roof() + 1;
			for (int y = from; y >= to; y--) {
				BlockState want = y == to ? VINE_TIP : VINE_BODY;
				if (level.getBlockState(at.set(v[0], y, v[1])) != want) level.setBlock(at, want, Block.UPDATE_CLIENTS);
			}
		}
	}

	// ------------------------------------------------------------------ hand-built places

	private void planBuilds() {
		int n = this.layout.deepLayers;
		// N5: the Anchor Shrine around the top of its own tower
		BlockPos anchor = this.layout.anchorShrine(this.seed, this.bounds);
		this.shrine(anchor);
		// N19: the Ember Forge on its plinth
		this.forge(this.layout.emberForge(this.seed, this.bounds), n);
		// N3: small nether-brick rooms in the warrens, N8: bartering stalls, N11: piglin mining camps
		int count = Math.max(2, this.bounds.length() / 75);
		for (int i = 0; i < count; i++) {
			this.room(3, i);
			this.stall(8, i);
			this.camp(11, i);
		}
	}

	private void shrine(BlockPos anchor) {
		int cx = anchor.getX(), cz = anchor.getZ() - 3, y0 = anchor.getY() - 1; // the floor is the tower roof's level
		BlockState bricks = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), floor = Blocks.CRYING_OBSIDIAN.defaultBlockState();
		int minX = Math.max(this.bounds.minX(), cx - 4), maxX = Math.min(this.bounds.maxX(), cx + 4);
		int minZ = cz - 4, maxZ = cz + 6;
		Shape shape = (x, y, z) -> {
			if (Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 2 && y <= y0) return null; // the tower
			boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
			if (y == y0) return Math.floorMod(x + z, 4) == 0 ? Blocks.GLOWSTONE.defaultBlockState() : floor;
			if (y == y0 + 6) return edge || Math.floorMod(x + z, 3) == 0 ? bricks : Blocks.SHROOMLIGHT.defaultBlockState();
			if (edge) {
				boolean door = (z == minZ || z == maxZ) && Math.abs(x - this.bounds.centerX()) <= 1 && y <= y0 + 3;
				return door ? AIR : bricks;
			}
			if (x == anchor.getX() && z == anchor.getZ() && y == anchor.getY()) {
				return Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 4);
			}
			if (y == anchor.getY() && Math.abs(x - anchor.getX()) == 1 && Math.abs(z - anchor.getZ()) == 1) return Blocks.GLOWSTONE.defaultBlockState();
			return AIR;
		};
		this.builds.add(new Build(5, minX, y0, minZ, maxX, y0 + 6, maxZ, shape, List.of()));
	}

	private void forge(BlockPos chest, int layer) {
		int cx = chest.getX(), cz = chest.getZ() - 3, top = chest.getY() - 1; // plinth top (-2005); the tower stands in it
		int bottom = this.layout.layerBottom(layer) + 9;
		int minX = Math.max(this.bounds.minX(), cx - 4), maxX = Math.min(this.bounds.maxX(), cx + 4);
		int minZ = cz - 4, maxZ = cz + 7;
		BlockState plinth = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), crying = Blocks.CRYING_OBSIDIAN.defaultBlockState();
		BlockState roof = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
		Shape shape = (x, y, z) -> {
			if (Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 2 && y <= top) return null; // the tower
			boolean corner = (x == minX || x == maxX) && (z == minZ || z == maxZ);
			if (y < top) return plinth;
			if (y == top) return Math.floorMod(x * 3 + z, 5) == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState() : plinth;
			if (y == top + 6) return corner ? crying : (x == minX || x == maxX || z == minZ || z == maxZ) ? plinth : roof;
			if (corner) return crying;
			if (x == chest.getX() && z == chest.getZ() && y == chest.getY()) return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
			if (y == top + 1 && x == chest.getX() && (z == chest.getZ() + 1 || z == chest.getZ() - 1)) return Blocks.LAVA_CAULDRON.defaultBlockState();
			if (y == top + 1 && (x == minX + 1 || x == maxX - 1) && z == maxZ - 1) return Blocks.SPAWNER.defaultBlockState();
			if (y == top + 1 && z == maxZ - 2 && x == chest.getX()) return Blocks.ANVIL.defaultBlockState();
			return AIR;
		};
		List<ItemStack> loot = List.of(
			new ItemStack(ModContent.EMBER_KEY), new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2), new ItemStack(Items.NETHERITE_INGOT), new ItemStack(Items.LODESTONE)
		);
		List<Entity> entities = List.of(
			new Entity(chest, null, loot, null),
			new Entity(new BlockPos(minX + 1, top + 1, maxZ - 1), null, null, EntityTypes.BLAZE),
			new Entity(new BlockPos(maxX - 1, top + 1, maxZ - 1), null, null, EntityTypes.MAGMA_CUBE)
		);
		this.builds.add(new Build(layer, minX, bottom, minZ, maxX, top + 6, maxZ, shape, entities));
	}

	/** A spot for a build of a layer: (x, floor y, z) on a cavern floor, or null. */
	private @Nullable BlockPos spot(int layer, int salt, int halfX, int halfZ) {
		RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, salt));
		int lava = this.layout.lavaTop(layer), top = this.layout.layerTop(layer);
		for (int attempt = 0; attempt < 40; attempt++) {
			int x = this.bounds.minX() + halfX + 1 + random.nextInt(Math.max(1, this.bounds.width() - 2 * halfX - 2));
			int z = this.bounds.minZ() + halfZ + 4 + random.nextInt(Math.max(1, this.bounds.length() - 2 * halfZ - 8));
			if (this.nearPath(layer, x, z, Math.max(halfX, halfZ) + 3)) continue;
			int y = this.floorAbove(layer, x, z, lava + 3 + random.nextInt(Math.max(1, (top - lava) / 2)), top - 12);
			if (y == Integer.MIN_VALUE) continue;
			return new BlockPos(x, y, z);
		}
		return null;
	}

	/** Netherrack Warrens: a 7x5x7 nether-brick room with a fortress chest, opened into the tunnel it sits on. */
	private void room(int layer, int i) {
		RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, 15000 + i));
		int lava = this.layout.lavaTop(layer), top = this.layout.layerTop(layer);
		BlockPos at = null;
		for (int attempt = 0; attempt < 60 && at == null; attempt++) {
			int x = this.bounds.minX() + 4 + random.nextInt(Math.max(1, this.bounds.width() - 8));
			int z = this.bounds.minZ() + 6 + random.nextInt(Math.max(1, this.bounds.length() - 12));
			int y = lava + 6 + random.nextInt(Math.max(1, top - lava - 20));
			if (this.nearPath(layer, x, z, 8) || !this.carved(layer, x, y, z)) continue;
			at = new BlockPos(x, y, z);
		}
		if (at == null) return;
		int cx = at.getX(), cy = at.getY() - 1, cz = at.getZ();
		BlockPos chest = new BlockPos(cx + 2, cy + 1, cz + 2);
		Shape shape = (x, y, z) -> {
			boolean shell = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3 || y == cy || y == cy + 4;
			if (!shell) return x == chest.getX() && y == chest.getY() && z == chest.getZ() ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH) : AIR;
			boolean door = y > cy && y <= cy + 2 && (x == cx || z == cz);
			if (door) return AIR;
			if (y == cy + 4 && x == cx && z == cz) return GLOWSTONE;
			return this.hash01(x, y, z, 15100) < 0.2 ? Blocks.CRACKED_NETHER_BRICKS.defaultBlockState() : BRICKS;
		};
		this.builds.add(new Build(layer, cx - 3, cy, cz - 3, cx + 3, cy + 4, cz + 3, shape, List.of(new Entity(chest, BuiltInLootTables.NETHER_BRIDGE, null, null))));
	}

	/** Wart Terraces: an abandoned bartering stall (crimson posts, a slab roof, a chest of gold). */
	private void stall(int layer, int i) {
		BlockPos at = this.spot(layer, 15200 + i, 2, 2);
		if (at == null) return;
		int cx = at.getX(), y0 = at.getY(), cz = at.getZ();
		BlockPos chest = new BlockPos(cx, y0, cz);
		Shape shape = (x, y, z) -> {
			int adx = Math.abs(x - cx), adz = Math.abs(z - cz);
			if (y == y0 - 1) return Blocks.CRIMSON_PLANKS.defaultBlockState();
			if (y == y0 + 3) return Blocks.CRIMSON_SLAB.defaultBlockState();
			if (adx == 2 && adz == 2) return Blocks.CRIMSON_FENCE.defaultBlockState();
			if (x == cx && z == cz && y == y0) return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
			if (y == y0 && adx == 1 && adz == 0) return Blocks.GOLD_BLOCK.defaultBlockState();
			return AIR;
		};
		List<ItemStack> gold = List.of(new ItemStack(Items.GOLD_INGOT, 5 + i % 4), new ItemStack(Items.GOLD_NUGGET, 18), new ItemStack(Items.GOLDEN_CARROT, 4), new ItemStack(Items.NETHER_WART, 12));
		this.builds.add(new Build(layer, cx - 2, y0 - 1, cz - 2, cx + 2, y0 + 3, cz + 2, shape, List.of(new Entity(chest, null, gold, null))));
	}

	/** Piglin Mines: a mining camp (blackstone floor, soul campfire, gold blocks, a bastion chest). */
	private void camp(int layer, int i) {
		BlockPos at = this.spot(layer, 15300 + i, 3, 3);
		if (at == null) return;
		int cx = at.getX(), y0 = at.getY(), cz = at.getZ();
		BlockPos chest = new BlockPos(cx + 2, y0, cz - 2);
		Shape shape = (x, y, z) -> {
			int adx = Math.abs(x - cx), adz = Math.abs(z - cz);
			if (y == y0 - 1) return adx + adz <= 1 ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE.defaultBlockState();
			if (y == y0 && x == cx && z == cz) return Blocks.SOUL_CAMPFIRE.defaultBlockState();
			if (y == y0 && x == chest.getX() && z == chest.getZ()) return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST);
			if (y == y0 && adx == 3 && adz == 3) return Blocks.GOLD_BLOCK.defaultBlockState();
			if (y == y0 + 1 && adx == 3 && adz == 3 && (x + z) % 2 == 0) return Blocks.GOLD_BLOCK.defaultBlockState();
			if (y == y0 && x == cx - 2 && z == cz + 2) return Blocks.BARREL.defaultBlockState();
			return y >= y0 && y <= y0 + 3 ? AIR : null;
		};
		this.builds.add(new Build(layer, cx - 3, y0 - 1, cz - 3, cx + 3, y0 + 3, cz + 3, shape, List.of(new Entity(chest, BuiltInLootTables.BASTION_OTHER, null, null))));
	}

	// ------------------------------------------------------------------ ores

	private static final double ORE_MULTIPLIER = 2.5;

	public void placeOres(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int minX = Math.max(pos.getMinBlockX(), this.bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), this.bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), this.bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), this.bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		double areaScale = (maxX - minX + 1) * (maxZ - minZ + 1) / 256.0;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, 78), Strip.mix(pos.x(), pos.z() * 31L + 7));
		BlockState quartz = Blocks.NETHER_QUARTZ_ORE.defaultBlockState(), gold = Blocks.NETHER_GOLD_ORE.defaultBlockState(), debris = Blocks.ANCIENT_DEBRIS.defaultBlockState();
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) {
			NetherRow row = this.rows[layer];
			int bottom = this.layout.layerBottom(layer) + 9, top = this.layout.layerTop(layer);
			this.veins(chunk, random, layer, 8 * row.quartz() * ORE_MULTIPLIER * areaScale, 8, quartz, minX, maxX, minZ, maxZ, bottom, top);
			this.veins(chunk, random, layer, 5 * row.gold() * ORE_MULTIPLIER * areaScale, 6, gold, minX, maxX, minZ, maxZ, bottom, top);
			this.veins(chunk, random, layer, row.debris() * areaScale, 2, debris, minX, maxX, minZ, maxZ, bottom, top);
		}
	}

	private void veins(ChunkAccess chunk, RandomSource random, int layer, double expected, int size, BlockState ore, int minX, int maxX, int minZ, int maxZ, int bottom, int top) {
		ChunkPos pos = chunk.getPos();
		int veins = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
		for (int v = 0; v < veins; v++) {
			int x = minX + random.nextInt(maxX - minX + 1), z = minZ + random.nextInt(maxZ - minZ + 1), y = bottom + random.nextInt(top - bottom + 1);
			for (int i = 0; i < size; i++) {
				if (x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= bottom && y <= top) {
					int lx = x - pos.getMinBlockX(), lz = z - pos.getMinBlockZ();
					BlockState current = get(chunk, lx, y, lz);
					for (BlockState stone : this.layerStones[layer]) {
						if (current == stone) {
							set(chunk, lx, y, lz, ore.is(Blocks.NETHER_GOLD_ORE) && current.is(Blocks.BLACKSTONE) ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : ore);
							break;
						}
					}
				}
				switch (random.nextInt(6)) {
					case 0 -> x++;
					case 1 -> x--;
					case 2 -> y++;
					case 3 -> y--;
					case 4 -> z++;
					default -> z--;
				}
			}
		}
	}

	// ------------------------------------------------------------------ decoration

	private enum Mode { FLOOR, CEILING }

	private record Placement(ResourceKey<Feature> feature, double perChunk, Mode mode) {}

	private static Placement p(ResourceKey<Feature> feature, double perChunk, Mode mode) {
		return new Placement(feature, perChunk, mode);
	}

	private static List<Placement> placements(int layer) {
		return switch (layer) {
			case 1 -> List.of(p(NetherFeatures.GLOWSTONE_EXTRA, 2, Mode.CEILING), p(NetherFeatures.FIRE, 0.5, Mode.FLOOR));
			case 2 -> List.of(p(TreeFeatures.CRIMSON_FUNGUS_PLANTED, 4, Mode.FLOOR), p(NetherFeatures.CRIMSON_FOREST_VEGETATION, 3, Mode.FLOOR),
				p(NetherFeatures.WEEPING_VINES, 4, Mode.CEILING));
			case 4 -> List.of(p(NetherFeatures.BASALT_PILLAR, 1.5, Mode.FLOOR), p(NetherFeatures.SOUL_FIRE, 1, Mode.FLOOR));
			case 5 -> List.of(p(TreeFeatures.WARPED_FUNGUS_PLANTED, 4, Mode.FLOOR), p(NetherFeatures.WARPED_FOREST_VEGETION, 3, Mode.FLOOR),
				p(NetherFeatures.TWISTING_VINES, 2, Mode.FLOOR));
			case 6 -> List.of(p(NetherFeatures.GLOWSTONE_EXTRA, 6, Mode.CEILING));
			case 7 -> List.of(p(NetherFeatures.DELTA, 3, Mode.FLOOR), p(NetherFeatures.LARGE_BASALT_COLUMNS, 1, Mode.FLOOR), p(NetherFeatures.SMALL_BASALT_COLUMNS, 2, Mode.FLOOR));
			case 10 -> List.of(p(TreeFeatures.WARPED_FUNGUS_PLANTED, 2, Mode.FLOOR), p(NetherFeatures.NETHER_SPROUTS, 1, Mode.FLOOR));
			case 13 -> List.of(p(NetherFeatures.GLOWSTONE_EXTRA, 1, Mode.CEILING));
			case 14 -> List.of(p(NetherFeatures.WEEPING_VINES, 6, Mode.CEILING), p(NetherFeatures.TWISTING_VINES, 6, Mode.FLOOR), p(NetherFeatures.WARPED_FOREST_VEGETION, 2, Mode.FLOOR));
			case 16 -> List.of(p(NetherFeatures.FIRE, 1, Mode.FLOOR));
			case 17 -> List.of(p(NetherFeatures.SOUL_FIRE, 2, Mode.FLOOR), p(NetherFeatures.BASALT_PILLAR, 0.5, Mode.FLOOR));
			case 18 -> List.of(p(NetherFeatures.SMALL_BASALT_COLUMNS, 1, Mode.FLOOR));
			case 19 -> List.of(p(NetherFeatures.DELTA, 2, Mode.FLOOR), p(NetherFeatures.FIRE, 0.5, Mode.FLOOR));
			default -> List.of();
		};
	}

	/** The deep layers' features and the hand-built places' block entities, for one decorated chunk. */
	public void decorate(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int minX = Math.max(pos.getMinBlockX(), this.bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), this.bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), this.bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), this.bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		double areaScale = (maxX - minX + 1) * (maxZ - minZ + 1) / 256.0;
		var features = level.registryAccess().lookupOrThrow(Registries.FEATURE);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) {
			RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, 16000 + layer), Strip.mix(pos.x(), pos.z()));
			int lava = this.layout.lavaTop(layer), top = this.layout.layerTop(layer);
			for (Placement placement : placements(layer)) {
				Holder<Feature> feature = features.get(placement.feature()).orElse(null);
				double expected = placement.perChunk() * areaScale;
				int count = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
				for (int i = 0; i < count; i++) {
					int x = Math.clamp(minX + random.nextInt(maxX - minX + 1), this.bounds.minX() + 3, this.bounds.maxX() - 3);
					int z = Math.clamp(minZ + random.nextInt(maxZ - minZ + 1), this.bounds.minZ() + 3, this.bounds.maxZ() - 3);
					if (feature == null || this.nearPath(layer, x, z, 3) || this.inBuild(layer, x, z, 3)) continue;
					int y = lava + 2 + random.nextInt(Math.max(1, top - lava - 6));
					BlockPos origin = placement.mode() == Mode.FLOOR ? this.floorNear(level, x, y, z, lava + 1, top - 2, at) : this.ceilingNear(level, x, y, z, lava + 4, top, at);
					if (origin != null) feature.value().place(level, generator, random, origin);
				}
			}
			this.curtains(level, layer, minX, maxX, minZ, maxZ, areaScale, new XoroshiroRandomSource(Strip.mix(this.seed, 16800 + layer), Strip.mix(pos.x(), pos.z())), at);
		}
		// block entities of the hand-built places in this chunk
		RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, 16500), Strip.mix(pos.x(), pos.z()));
		for (Build build : this.builds) {
			// blocks with a block entity besides the chests and spawners (the camps' soul campfires and
			// barrels): cutPaths wrote them raw into the proto chunk; setting them again creates it
			if (!build.intersects(pos.getMinBlockX(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockZ())) continue;
			for (int x = Math.max(build.minX(), pos.getMinBlockX()); x <= Math.min(build.maxX(), pos.getMaxBlockX()); x++) {
				for (int z = Math.max(build.minZ(), pos.getMinBlockZ()); z <= Math.min(build.maxZ(), pos.getMaxBlockZ()); z++) {
					if (!this.bounds.inside(x, z)) continue;
					for (int y = build.minY(); y <= build.maxY(); y++) {
						BlockState want = build.shape().at(x, y, z);
						if (want == null || !want.hasBlockEntity() || want.is(Blocks.CHEST) || want.is(Blocks.SPAWNER)) continue;
						BlockState current = level.getBlockState(at.set(x, y, z));
						if (current.is(want.getBlock()) && level.getBlockEntity(at) == null) level.setBlock(at, current, Block.UPDATE_CLIENTS);
					}
				}
			}
		}
		for (Build build : this.builds) {
			for (Entity e : build.entities()) {
				BlockPos p = e.pos();
				if (p.getX() < pos.getMinBlockX() || p.getX() > pos.getMaxBlockX() || p.getZ() < pos.getMinBlockZ() || p.getZ() > pos.getMaxBlockZ()) continue;
				if (e.spawner() != null) {
					level.setBlock(p, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_CLIENTS); // also creates the block entity
					if (level.getBlockEntity(p) instanceof SpawnerBlockEntity be) be.setEntityId(e.spawner(), random);
					continue;
				}
				BlockState state = level.getBlockState(p);
				if (!state.is(Blocks.CHEST)) {
					state = Blocks.CHEST.defaultBlockState();
					level.setBlock(p, state, Block.UPDATE_CLIENTS);
				} else {
					level.setBlock(p, state, Block.UPDATE_CLIENTS); // creates the block entity terrain generation left out
				}
				if (e.loot() != null) {
					RandomizableContainer.setBlockEntityLootTable(level, random, p, e.loot());
				} else if (e.items() != null && level.getBlockEntity(p) instanceof ChestBlockEntity be) {
					for (int i = 0; i < e.items().size(); i++) be.setItem(4 + i * 2 % 23, e.items().get(i).copy());
				}
			}
		}
	}

	static final BlockState TWISTING_BODY = Blocks.TWISTING_VINES_PLANT.defaultBlockState();
	static final BlockState TWISTING_TIP = Blocks.TWISTING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, GrowingPlantHeadBlock.MAX_AGE);
	/** Wall curtains per (full) chunk and layer; the longest strand. */
	private static final double CURTAINS_PER_CHUNK = 0.7;
	private static final int CURTAIN_MAX = 96, CURTAIN_MIN = 10;

	/**
	 * Spec v2 4.1 Connections: vine curtains on the cavern walls, three strands wide along z, a
	 * climbable way between a layer's ledges. Weeping vines hang from a ceiling down along a wall;
	 * in the warped layers (warped_forest biome) twisting vines grow up a wall from the floor
	 * instead. Only inside this chunk (all three strands), off the paths and the builds.
	 */
	private void curtains(WorldGenLevel level, int layer, int minX, int maxX, int minZ, int maxZ, double areaScale, RandomSource random, BlockPos.MutableBlockPos at) {
		if (maxZ - minZ < 2) return;
		boolean twisting = this.rows[layer].biome() == net.minecraft.world.level.biome.Biomes.WARPED_FOREST;
		int lava = this.layout.lavaTop(layer), top = this.layout.layerTop(layer);
		double expected = CURTAINS_PER_CHUNK * areaScale;
		int count = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
		for (int i = 0; i < count; i++) {
			for (int attempt = 0; attempt < 12; attempt++) {
				int x = minX + random.nextInt(maxX - minX + 1);
				int z = minZ + 1 + random.nextInt(maxZ - minZ - 1);
				if (this.nearPath(layer, x, z, 4) || this.inBuild(layer, x, z, 3)) continue;
				if (this.curtain(level, x, z, twisting, lava, top, random, at)) break;
			}
		}
	}

	private boolean anchorBlock(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z) {
		BlockState state = level.getBlockState(at.set(x, y, z));
		return state.isSolid() && state.getFluidState().isEmpty();
	}

	/** The air run from y away from the anchor (dir = towards it), keeping one air block at the far end. */
	private int airRun(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int y, int z, int dir, int lava, int top, int max) {
		int length = 0;
		while (length < max) {
			int yy = y - dir * length;
			if (yy <= lava + 1 || yy >= top) break;
			if (!level.getBlockState(at.set(x, yy, z)).isAir() || !level.getBlockState(at.set(x, yy - dir, z)).isAir()) break;
			length++;
		}
		return length;
	}

	/**
	 * One curtain in column (x, z): the highest ceiling (weeping) or lowest floor (twisting) with a
	 * wall beside the strand's first four blocks and room for at least {@link #CURTAIN_MIN}; false
	 * (nothing placed) when the column has none.
	 */
	private boolean curtain(WorldGenLevel level, int x, int z, boolean twisting, int lava, int top, RandomSource random, BlockPos.MutableBlockPos at) {
		int dir = twisting ? -1 : 1; // towards the anchor: the floor below (twisting) or the ceiling above (weeping)
		int anchor = Integer.MIN_VALUE;
		for (int n = 0; n < top - lava - 4 && anchor == Integer.MIN_VALUE; n++) {
			int y = twisting ? lava + 2 + n : top - 2 - n;
			if (!level.getBlockState(at.set(x, y, z)).isAir() || !this.anchorBlock(level, at, x, y + dir, z)) continue;
			boolean wall = false;
			for (int side = -1; side <= 1 && !wall; side += 2) {
				if (!this.bounds.inside(x + side, z)) continue;
				wall = true;
				for (int d = 0; d < 4 && wall; d++) wall = level.getBlockState(at.set(x + side, y - dir * d, z)).isSolid();
			}
			if (wall && this.airRun(level, at, x, y, z, dir, lava, top, CURTAIN_MIN) >= CURTAIN_MIN) anchor = y;
		}
		if (anchor == Integer.MIN_VALUE) return false;
		for (int dz : new int[] {0, -1, 1}) {
			int zz = z + dz;
			// each side strand finds its own anchor near the middle one's
			int a = anchor;
			if (!level.getBlockState(at.set(x, a, zz)).isAir()) continue;
			for (int n = 0; n < 6 && level.getBlockState(at.set(x, a + dir, zz)).isAir(); n++) a += dir;
			if (!this.anchorBlock(level, at, x, a + dir, zz)) continue;
			int length = this.airRun(level, at, x, a, zz, dir, lava, top, CURTAIN_MAX - (dz == 0 ? 0 : random.nextInt(12)));
			if (length < 2) continue;
			for (int n = 0; n < length; n++) {
				boolean tip = n == length - 1;
				BlockState state = twisting ? (tip ? TWISTING_TIP : TWISTING_BODY) : (tip ? VINE_TIP : VINE_BODY);
				level.setBlock(at.set(x, a - dir * n, zz), state, Block.UPDATE_CLIENTS);
			}
		}
		return true;
	}

	private boolean inBuild(int layer, int x, int z, int margin) {
		for (Build build : this.builds) {
			if (build.layer() == layer && build.intersects(x - margin, z - margin, x + margin, z + margin)) return true;
		}
		return false;
	}

	private @Nullable BlockPos floorNear(WorldGenLevel level, int x, int y, int z, int min, int max, BlockPos.MutableBlockPos at) {
		for (int d = 0; d < 24; d++) {
			for (int yy : new int[] {y - d, y + d}) {
				if (yy <= min || yy >= max) continue;
				if (level.getBlockState(at.set(x, yy, z)).isAir() && level.getBlockState(at.set(x, yy + 1, z)).isAir()) {
					BlockState below = level.getBlockState(at.set(x, yy - 1, z));
					if (below.isSolid() && below.getFluidState().isEmpty()) return new BlockPos(x, yy, z);
				}
			}
		}
		return null;
	}

	private @Nullable BlockPos ceilingNear(WorldGenLevel level, int x, int y, int z, int min, int max, BlockPos.MutableBlockPos at) {
		for (int d = 0; d < 24; d++) {
			for (int yy : new int[] {y + d, y - d}) {
				if (yy <= min || yy >= max) continue;
				if (level.getBlockState(at.set(x, yy, z)).isAir() && level.getBlockState(at.set(x, yy + 1, z)).isSolid()) return new BlockPos(x, yy, z);
			}
		}
		return null;
	}
}
