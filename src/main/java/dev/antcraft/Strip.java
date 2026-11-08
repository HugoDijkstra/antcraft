package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

/**
 * All the geometry of the AntCraft world. The layer heights are fixed (the dimension type sets
 * the world height); the x/z footprint is per world and per dimension, see {@link StripBounds}.
 *
 * <pre>
 *  y  335  ─ top of the world (barrier walls go all the way up)
 *  y  ~64  ─ vanilla surface
 *  y  -61  ─ layer 0 (vanilla underground) ends
 *  y  -62..-64      bedrock floor 0 (one hole)
 *  layer 1 ...
 *  y -318..-320     bedrock floor 1 (one hole)
 *  ...
 *  y -1854..-1856   bedrock floor 7 (one hole)
 *  layer 8 (End portal room at the very bottom)
 *  y -2028..-2032   solid bedrock bottom
 * </pre>
 */
public final class Strip {
	private Strip() {}

	/**
	 * The x/z footprint of a strip dimension, or null if the level is not a strip. Each strip
	 * generator carries its own bounds (overworld and Nether share the world's width and length).
	 */
	public static @Nullable StripBounds bounds(Level level) {
		return level instanceof ServerLevel server ? bounds(server.getChunkSource().getGenerator()) : null;
	}

	public static @Nullable StripBounds bounds(ChunkGenerator generator) {
		return generator instanceof StripGenerator strip ? strip.bounds() : null;
	}

	/** World height, must match data/antcraft/dimension_type/strip_overworld.json. */
	public static final int WORLD_MIN_Y = -2032;
	public static final int WORLD_MAX_Y = 335; // inclusive; vanilla terrain needs up to 320

	/** Vanilla terrain lives in [-64, 320). Everything below is ours. */
	public static final int VANILLA_MIN_Y = -64;

	/** Solid bedrock bottom of the world. */
	public static final int BOTTOM_TOP_Y = -2028; // bedrock from WORLD_MIN_Y..BOTTOM_TOP_Y

	/** Holed bedrock floors: floor k occupies [floorBase(k), floorBase(k) + FLOOR_THICKNESS - 1]. */
	public static final int FLOOR_COUNT = 8;
	public static final int FLOOR_SPACING = 256;
	public static final int FLOOR_THICKNESS = 3;

	/** Number of deep layers (between floors / below the last floor). Layer 0 is vanilla. */
	public static final int DEEP_LAYERS = FLOOR_COUNT; // layers 1..8

	/** Height of the open "floor hall" above every bedrock floor, so the hole can be found. */
	public static final int HALL_HEIGHT = 6;

	/** Height of the open air part of an underground forest. */
	public static final int FOREST_HEIGHT = 40;

	/**
	 * The deep dark layer: below its forest lies an ancient city (y -1341..-1089). The city's
	 * jigsaw anchor sits ~24 blocks above its floor, its bounding box spans about y -1317..-1263.
	 */
	public static final int CITY_LAYER = 5;

	/** The hole of this floor is covered by a Nether Seal; the Nether Key from the Nether strip opens it. */
	public static final int SEALED_FLOOR = 4;

	/** The hole of this floor is covered by an Ancient Seal; an Ancient Key from the ancient city opens it. */
	public static final int ANCIENT_SEALED_FLOOR = 7;

	/** Floors whose hall is a closed boss room; the hole opens when the floor's guardian dies. */
	public static final int[] GUARDIAN_FLOORS = {0, 2, 6};

	public static boolean isGuardianFloor(int k) {
		for (int floor : GUARDIAN_FLOORS) {
			if (floor == k) return true;
		}
		return false;
	}

	/** The underground desert. */
	public static final int DESERT_LAYER = 6;
	public static final int CITY_START_Y = -1280; // keep in sync with deep_ancient_city.json
	/** Vertical extent the city may occupy (for clipping its parts outside the strip). */
	public static final int CITY_MIN_Y = CITY_START_Y - 40, CITY_MAX_Y = CITY_START_Y + 60;

	public static int floorBase(int k) {
		return VANILLA_MIN_Y - FLOOR_SPACING * k;
	}

	public static int floorTop(int k) {
		return floorBase(k) + FLOOR_THICKNESS - 1;
	}

	/** Lowest non-bedrock y of deep layer {@code layer} (1..8). */
	public static int layerBottom(int layer) {
		return layer < DEEP_LAYERS ? floorTop(layer) + 1 : BOTTOM_TOP_Y + 1;
	}

	/** Highest y of deep layer {@code layer} (1..8). */
	public static int layerTop(int layer) {
		return floorBase(layer - 1) - 1;
	}

	/** Lowest block of the floor-hall air above the floor that bounds layer {@code layer}'s bottom; 0 = vanilla layer. */
	public static int hallBottom(int layer) {
		return layer == 0 ? floorTop(0) + 1 : layerBottom(layer);
	}

	/** y of the grass surface (before rolling-hill offset) in the forest of a deep layer. */
	public static int forestFloor(int layer) {
		int bottom = layerBottom(layer);
		int top = layerTop(layer);
		return bottom + (int) ((top - bottom) * 0.45);
	}

	/** Layer index for a y below vanilla range: 1..8, or 0 for y >= floorTop(0)+1, -1 for floors/bottom. */
	public static int layerAt(int y) {
		if (y > floorTop(0)) return 0;
		for (int layer = 1; layer <= DEEP_LAYERS; layer++) {
			if (y <= layerTop(layer) && y >= layerBottom(layer)) return layer;
		}
		return -1;
	}

	/** Floor index if y is inside a holed bedrock floor, else -1. */
	public static int floorAt(int y) {
		for (int k = 0; k < FLOOR_COUNT; k++) {
			if (y >= floorBase(k) && y <= floorTop(k)) return k;
		}
		return -1;
	}

	/** The ancient city starts in this chunk, in the middle of the strip. */
	public static ChunkPos cityChunk(StripBounds bounds) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.centerZ() >> 4);
	}

	/**
	 * How many times longer pickaxe blocks take to mine in a deep layer (1 = vanilla). The client
	 * uses the server's value, see {@link MiningHardness}.
	 */
	public static float hardness(int layer, boolean clientSide) {
		return MiningHardness.factor(layer, clientSide);
	}

	// ---------------------------------------------------------------- villages

	/** Village type in each deep layer's forest (index = layer). */
	private static final String[] VILLAGE_TYPES = {null, "plains", "taiga", "plains", "savanna", "plains", "desert", "taiga", "snowy"};

	public static String villageType(int layer) {
		return VILLAGE_TYPES[layer];
	}

	/**
	 * Chunk where the village of a deep layer starts: alternating sides, a different chunk row per
	 * layer, never in the first or last chunk row (the village is moved to the strip's centre x later).
	 *
	 * <p>Layer L sits {@code (L - 1) * step} rows after a seeded first row (wrapping around). The step
	 * grows with the strip length so long strips spread their villages out, and it is coprime with
	 * the number of rows, so all eight layers get different rows whenever there are at least eight
	 * rows (a strip of the minimum length 150 has exactly eight). Two layers with the same village
	 * type in the same chunk would otherwise overwrite each other's structure start.
	 */
	public static ChunkPos villageChunk(long seed, int layer, StripBounds bounds) {
		int firstRow = bounds.minZ() >> 4, lastRow = bounds.maxZ() >> 4;
		int rows = Math.max(1, lastRow - firstRow - 1); // 17 for the default 300-long strip
		int base = (int) Math.floorMod(mix(seed, 7000), (long) rows);
		int chunkX = layer % 2 == 0 ? bounds.minX() >> 4 : bounds.maxX() >> 4;
		return new ChunkPos(chunkX, firstRow + 1 + Math.floorMod(base + layer * villageStep(rows), rows));
	}

	// ---------------------------------------------------------------- underground desert pyramid

	/** The pyramid is 21 x 21 blocks; its north edge sits this far into its start chunk. */
	public static final int PYRAMID_SIZE = 21, PYRAMID_OFFSET_Z = 4;

	/**
	 * Chunk where the desert pyramid of the underground desert (layer 6) starts: in the middle of the
	 * strip's width, in a seeded chunk row at least six rows (96 blocks) from the layer's village if
	 * the strip is long enough (else as far as it can be), preferably not under the hole of the
	 * floor above, and always with the whole pyramid inside the strip's length.
	 */
	public static ChunkPos pyramidChunk(long seed, StripBounds bounds) {
		int firstRow = (bounds.minZ() + 1 - PYRAMID_OFFSET_Z + 15) >> 4; // north edge at least 1 block from the wall
		int lastRow = (bounds.maxZ() - 1 - PYRAMID_OFFSET_Z - (PYRAMID_SIZE - 1)) >> 4;
		if (lastRow < firstRow) lastRow = firstRow;
		int rows = lastRow - firstRow + 1;
		int villageRow = villageChunk(seed, DESERT_LAYER, bounds).z();
		int holeZ = holeZ(seed, DESERT_LAYER - 1, bounds);
		int startIndex = (int) Math.floorMod(mix(seed, 8000), (long) rows);
		int best = firstRow, bestScore = -1;
		for (int i = 0; i < rows; i++) {
			int row = firstRow + (startIndex + i) % rows;
			int north = (row << 4) + PYRAMID_OFFSET_Z;
			boolean underHole = holeZ >= north - 6 && holeZ <= north + PYRAMID_SIZE + 5;
			int score = Math.min(Math.abs(row - villageRow), 6) * 2 + (underHole ? 0 : 1);
			if (score > bestScore) {
				bestScore = score;
				best = row;
			}
		}
		return new ChunkPos(bounds.centerX() >> 4, best);
	}

	/** Row step between consecutive layers' villages: about rows / 8 (2 for 17 rows), coprime with rows. */
	static int villageStep(int rows) {
		if (rows <= 2) return 1;
		int step = Math.max(2, rows / DEEP_LAYERS);
		while (gcd(step, rows) != 1) step++;
		return step;
	}

	private static int gcd(int a, int b) {
		while (b != 0) {
			int t = a % b;
			a = b;
			b = t;
		}
		return a;
	}

	// ---------------------------------------------------------------- seeded positions

	/** Deterministic 64-bit mix (SplitMix64 finalizer). */
	public static long mix(long seed, long salt) {
		long z = seed + salt * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Hole of floor k: at least 2 blocks from the walls (x in [2, 17], z in [2, 297] for 20 x 300). */
	public static int holeX(long seed, int k, StripBounds bounds) {
		return bounds.minX() + 2 + (int) Math.floorMod(mix(seed, 1000 + k), (long) (bounds.width() - 4));
	}

	public static int holeZ(long seed, int k, StripBounds bounds) {
		int range = bounds.length() - 4;
		int z = bounds.minZ() + 2 + (int) Math.floorMod(mix(seed, 2000 + k), (long) range);
		if (k == FLOOR_COUNT - 1) {
			// the last hole must not come down on top of the End portal room
			int room = portalRoomZ(seed, bounds);
			while (Math.abs(z - room) <= PortalRoom.HALF_LENGTH + 3) {
				z = bounds.minZ() + 2 + Math.floorMod(z - bounds.minZ() - 2 + 2 * PortalRoom.HALF_LENGTH + 7, range);
			}
		}
		return z;
	}

	/** Centre Z of the End portal room in the bottom layer: at least 30 blocks from either end. */
	public static int portalRoomZ(long seed, StripBounds bounds) {
		return bounds.minZ() + 30 + (int) Math.floorMod(mix(seed, 3000), (long) (bounds.length() - 59));
	}

	public static int portalRoomFloorY() {
		return layerBottom(DEEP_LAYERS); // stone-brick floor sits right on the bottom bedrock
	}

	/** Where eyes of ender should fly: the middle of the portal. */
	public static BlockPos portalTarget(long seed, StripBounds bounds) {
		return new BlockPos(PortalRoom.portalX(bounds), portalRoomFloorY() + 2, portalRoomZ(seed, bounds));
	}
}
