package dev.antcraft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * The vertical geometry of the strip Nether (spec v2 section 4). Layouts 1 and 2 are the original
 * Nether (vanilla 0..127 in a 0..255 dimension, no deep layers); they differ only in where the
 * fortress, the key shrine and the bastion go along the strip ({@link NetherStripChunkGenerator}).
 * Layout 3 is the tall Nether: the vanilla Nether moved up by 1904 to 1904..2031, and 19 deep
 * layers 208 apart below it, world -2032..2031.
 *
 * <pre>
 *  y 2027..2031  vanilla bedrock roof (top of the world)
 *  y 1974        Key Shrine floor, lava sea at 1935
 *  y 1904..1906  NF0 (the vanilla bedrock floor, reshaped), hall 1907..1912 (closed tunnel)
 *  N1 1699..1903
 *  y 1696..1698  NF1
 *  ...
 *  y -1840..-1838 NF18
 *  N19 -2027..-1841 (Ember Forge)
 *  y -2032..-2028 bedrock bottom
 * </pre>
 *
 * The geometry methods are pure arithmetic; the layer table is built on first use (block states).
 */
public final class NetherLayout implements ArenaLayout {
	public static final NetherLayout LAYOUT_1 = new NetherLayout(1, 0, 0, 255, 0, 0, Map.of());
	public static final NetherLayout LAYOUT_2 = new NetherLayout(2, 0, 0, 255, 0, 0, Map.of());
	public static final NetherLayout LAYOUT_3 = new NetherLayout(
		3, 1904, -2032, 2031, 19, 208,
		Map.of(2, GuardianId.HOGLIN_WARLORD, 7, GuardianId.MOLTEN_SOVEREIGN, 12, GuardianId.BRUTE_WARLORD, 17, GuardianId.BONE_COLOSSUS)
	);

	private static final NetherLayout[] BY_ID = {null, LAYOUT_1, LAYOUT_2, LAYOUT_3};
	public static final int LATEST = 3;

	public static NetherLayout byId(int id) {
		if (id < 1 || id >= BY_ID.length) throw new IllegalArgumentException("Unknown AntCraft Nether layout " + id);
		return BY_ID[id];
	}

	public final int id;
	/** D_N: how far the vanilla Nether is moved up (0 = not moved). */
	public final int frameOffset;
	public final int worldMinY, worldMaxY;
	/** Holed floors NF0..NF(floorCount-1) = number of deep layers (0 in the old layouts). */
	public final int floorCount, deepLayers;
	public final int spacing;
	public final int floorThickness = 3, hallHeight = 6;
	/** Solid bedrock bottom: worldMinY..bottomTopY. */
	public final int bottomTopY;
	private final int[] guardianFloors;
	private final Map<Integer, GuardianId> guardians;
	private volatile NetherRow @Nullable [] rows;

	private NetherLayout(int id, int frameOffset, int worldMinY, int worldMaxY, int floorCount, int spacing, Map<Integer, GuardianId> guardians) {
		this.id = id;
		this.frameOffset = frameOffset;
		this.worldMinY = worldMinY;
		this.worldMaxY = worldMaxY;
		this.floorCount = floorCount;
		this.deepLayers = floorCount;
		this.spacing = spacing;
		this.bottomTopY = worldMinY + 4;
		this.guardians = Collections.unmodifiableMap(new LinkedHashMap<>(guardians));
		this.guardianFloors = guardians.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	public boolean tall() {
		return this.frameOffset != 0;
	}

	/** Lowest y of the moved vanilla Nether (= floorBase(0)). */
	public int vanillaMinY() {
		return this.frameOffset;
	}

	/** The deep layer table (index = layer, 1..deepLayers; 0 unused). */
	public NetherRow row(int layer) {
		NetherRow[] table = this.rows;
		if (table == null) {
			synchronized (this) {
				table = this.rows;
				if (table == null) this.rows = table = NetherRow.table(this);
			}
		}
		return table[layer];
	}

	// ------------------------------------------------------------------ floors and layers

	/** Lowest y of floor NFk (k = 0..floorCount-1). */
	public int floorBase(int k) {
		return this.frameOffset - this.spacing * k;
	}

	@Override
	public int floorTop(int k) {
		return this.floorBase(k) + this.floorThickness - 1;
	}

	public int layerBottom(int layer) {
		return layer < this.deepLayers ? this.floorTop(layer) + 1 : this.bottomTopY + 1;
	}

	public int layerTop(int layer) {
		return this.floorBase(layer - 1) - 1;
	}

	/** First air row of the causeway hall above the floor at the bottom of {@code layer} (0 = the NF0 tunnel). */
	public int hallBottom(int layer) {
		return layer == 0 ? this.floorTop(0) + 1 : this.layerBottom(layer);
	}

	/** Top of the lava sea of a deep layer (spec: bottom + 17; the Lava Archipelago's lake is higher). */
	public int lavaTop(int layer) {
		return this.layerBottom(layer) + this.row(layer).lavaHeight();
	}

	/** Layer for a y: 0 in the vanilla band, 1..deepLayers, -1 inside floors and the bottom. */
	public int layerAt(int y) {
		if (!this.tall()) return 0;
		if (y > this.floorTop(0)) return 0;
		for (int layer = 1; layer <= this.deepLayers; layer++) {
			if (y <= this.layerTop(layer) && y >= this.layerBottom(layer)) return layer;
		}
		return -1;
	}

	public int floorAt(int y) {
		for (int k = 0; k < this.floorCount; k++) {
			if (y >= this.floorBase(k) && y <= this.floorTop(k)) return k;
		}
		return -1;
	}

	// ------------------------------------------------------------------ gates

	@Override
	public int arenaLayoutId() {
		return this.id;
	}

	@Override
	public int hallHeight() {
		return this.hallHeight;
	}

	public boolean isGuardianFloor(int k) {
		return this.guardians.containsKey(k);
	}

	@Override
	public int[] guardianFloors() {
		return this.guardianFloors.clone();
	}

	@Override
	public @Nullable GuardianId guardian(int k) {
		return this.guardians.get(k);
	}

	// ------------------------------------------------------------------ holes and towers

	/** Hole of floor NFk: at least 2 blocks from the walls (the stairwell tower under it fits the strip). */
	@Override
	public int holeX(long seed, int k, StripBounds bounds) {
		return bounds.minX() + 2 + (int) Math.floorMod(Strip.mix(seed, 11000 + k), (long) (bounds.width() - 4));
	}

	/**
	 * Hole z of floor NFk. A hole keeps 8 blocks from the tower under the hole above it (in the same
	 * hall), and a guardian floor's hole keeps 24 from it, so that tower never comes down inside the
	 * boss room.
	 */
	@Override
	public int holeZ(long seed, int k, StripBounds bounds) {
		int range = bounds.length() - 4;
		int z = bounds.minZ() + 2 + (int) Math.floorMod(Strip.mix(seed, 12000 + k), (long) range);
		if (k == 0) {
			// clear of the Key Shrine's stair shaft, which comes down into the NF0 tunnel
			int shaft = bounds.scaledZ(120) - KeyShrine.CORRIDOR - 3;
			for (int i = 0; i < 8 && Math.abs(z - shaft) < 6; i++) z = bounds.minZ() + 2 + Math.floorMod(z - bounds.minZ() - 2 + 13, range);
			return z;
		}
		int above = this.towerZ(seed, k, bounds, this.holeZ(seed, k - 1, bounds));
		int keep = this.isGuardianFloor(k) ? ArenaManager.WALL_OFFSET + 12 : 8;
		for (int i = 0; i < 64 && Math.abs(z - above) < keep; i++) {
			z = bounds.minZ() + 2 + Math.floorMod(z - bounds.minZ() - 2 + 2 * keep + 5, range);
		}
		return z;
	}

	/** Centre z of the tower under a hole: the vine column runs at hole z + 1 (it hangs from the floor's bedrock). */
	public int towerZ(long seed, int layer, StripBounds bounds, int holeZAbove) {
		return Math.clamp(holeZAbove + 1, bounds.minZ() + 2, bounds.maxZ() - 2);
	}

	/**
	 * The stairwell towers of a layer (centre x, centre z): the one under the hole above, and one
	 * about every 75 blocks along the strip with seeded offsets, never in or near a boss room or the
	 * hole of the layer's own floor.
	 */
	public int[][] towers(long seed, int layer, StripBounds bounds) {
		java.util.List<int[]> list = new java.util.ArrayList<>();
		int hx = this.holeX(seed, layer - 1, bounds), hz = this.holeZ(seed, layer - 1, bounds);
		int tz = this.towerZ(seed, layer, bounds, hz);
		list.add(new int[] {hx, tz});
		boolean last = layer >= this.deepLayers;
		int ownHoleZ = last ? Integer.MIN_VALUE / 2 : this.holeZ(seed, layer, bounds);
		int keep = !last && this.isGuardianFloor(layer) ? ArenaManager.WALL_OFFSET + 6 : 6;
		int offset = 20 + (int) Math.floorMod(Strip.mix(seed, 13000 + layer), 40L);
		for (int z = bounds.minZ() + offset; z <= bounds.maxZ() - 4; z += 75) {
			if (Math.abs(z - tz) < 20 || Math.abs(z - ownHoleZ) < keep) continue;
			int x = bounds.minX() + 3 + (int) Math.floorMod(Strip.mix(seed, 13100 + layer * 31L + z), (long) Math.max(1, bounds.width() - 6));
			list.add(new int[] {x, Math.clamp(z, bounds.minZ() + 2, bounds.maxZ() - 2)});
		}
		return list.toArray(int[][]::new);
	}

	/** Tower geometry, relative to the layer bottom: the roof (the vine column ends one above it). */
	public static final int TOWER_ROOF = 21;

	// ------------------------------------------------------------------ positions of the hand-built places

	/** Anchor Shrine (N5): the respawn anchor's position. */
	public BlockPos anchorShrine(long seed, StripBounds bounds) {
		int layer = 5;
		return new BlockPos(bounds.centerX(), this.layerBottom(layer) + 60, this.placeZ(seed, bounds, layer, 14100));
	}

	/** Ember Forge (N19): the chest on the plinth (plinth top at -2005). */
	public BlockPos emberForge(long seed, StripBounds bounds) {
		int layer = this.deepLayers;
		return new BlockPos(bounds.centerX(), this.layerBottom(layer) + 23, this.placeZ(seed, bounds, layer, 14200));
	}

	/** A z along the strip for a hand-built place in a layer: 30+ from the ends, 24+ from the towers and holes. */
	int placeZ(long seed, StripBounds bounds, int layer, int salt) {
		int range = Math.max(1, bounds.length() - 60);
		int z = bounds.minZ() + 30 + (int) Math.floorMod(Strip.mix(seed, salt), (long) range);
		int[][] towers = this.towers(seed, layer, bounds);
		int ownHole = layer < this.deepLayers ? this.holeZ(seed, layer, bounds) : Integer.MIN_VALUE / 2;
		for (int i = 0; i < 40; i++) {
			boolean clear = Math.abs(z - ownHole) >= 24;
			for (int[] t : towers) clear &= Math.abs(z - t[1]) >= 16;
			if (clear) break;
			z = bounds.minZ() + 30 + Math.floorMod(z - bounds.minZ() - 30 + 17, range);
		}
		return z;
	}

	@Override
	public String toString() {
		return "NetherLayout[" + this.id + "]";
	}
}
