package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * All the fixed geometry of the AntCraft world.
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

	/** Playable area, inclusive. */
	public static final int MIN_X = 0, MAX_X = 19;
	public static final int MIN_Z = 0, MAX_Z = 299;
	public static final int CENTER_X = 10, CENTER_Z = 150;

	/** Barrier wall coordinates (one block outside the playable area). */
	public static final int WALL_MIN_X = MIN_X - 1, WALL_MAX_X = MAX_X + 1;
	public static final int WALL_MIN_Z = MIN_Z - 1, WALL_MAX_Z = MAX_Z + 1;

	/** World height, must match data/minecraft/dimension_type/overworld.json. */
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

	/** The underground desert. */
	public static final int DESERT_LAYER = 6;
	public static final int CITY_START_Y = -1280; // keep in sync with deep_ancient_city.json
	/** The city starts in this chunk, in the middle of the strip. */
	public static final ChunkPos CITY_CHUNK = new ChunkPos(0, CENTER_Z >> 4);
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

	public static boolean inside(int x, int z) {
		return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
	}

	/** True for the barrier ring around the strip. */
	public static boolean isWall(int x, int z) {
		boolean inWallX = x >= WALL_MIN_X && x <= WALL_MAX_X;
		boolean inWallZ = z >= WALL_MIN_Z && z <= WALL_MAX_Z;
		return inWallX && inWallZ && !inside(x, z);
	}

	public static boolean chunkOverlapsStrip(ChunkPos pos) {
		return pos.getMaxBlockX() >= MIN_X && pos.getMinBlockX() <= MAX_X && pos.getMaxBlockZ() >= MIN_Z && pos.getMinBlockZ() <= MAX_Z;
	}

	/** True if the chunk has any strip or wall column. */
	public static boolean chunkTouchesStrip(ChunkPos pos) {
		return pos.getMaxBlockX() >= WALL_MIN_X && pos.getMinBlockX() <= WALL_MAX_X && pos.getMaxBlockZ() >= WALL_MIN_Z && pos.getMinBlockZ() <= WALL_MAX_Z;
	}

	/** Clamp a position into the strip, keeping {@code margin} blocks away from the walls. */
	public static BlockPos clampInside(BlockPos pos, int margin) {
		int x = Math.clamp(pos.getX(), MIN_X + margin, MAX_X - margin);
		int z = Math.clamp(pos.getZ(), MIN_Z + margin, MAX_Z - margin);
		return new BlockPos(x, pos.getY(), z);
	}

	/** How many times longer pickaxe blocks take to mine in a deep layer (1 = vanilla). */
	public static float hardness(int layer) {
		return layer <= 0 ? 1.0F : 1.0F + 0.5F * layer;
	}

	// ---------------------------------------------------------------- villages

	/** Village type in each deep layer's forest (index = layer). */
	private static final String[] VILLAGE_TYPES = {null, "plains", "taiga", "plains", "savanna", "plains", "desert", "taiga", "snowy"};

	public static String villageType(int layer) {
		return VILLAGE_TYPES[layer];
	}

	/** Chunk where the village of a deep layer starts: alternating sides, a different z per layer. */
	public static ChunkPos villageChunk(long seed, int layer) {
		int base = (int) Math.floorMod(mix(seed, 7000), 17L);
		return new ChunkPos(layer % 2, 1 + Math.floorMod(base + layer * 2, 17));
	}

	// ---------------------------------------------------------------- seeded positions

	/** Deterministic 64-bit mix (SplitMix64 finalizer). */
	public static long mix(long seed, long salt) {
		long z = seed + salt * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Hole of floor k: x in [2, 17], z in [2, 297]. */
	public static int holeX(long seed, int k) {
		return MIN_X + 2 + (int) Math.floorMod(mix(seed, 1000 + k), (long) (MAX_X - MIN_X - 3));
	}

	public static int holeZ(long seed, int k) {
		int z = MIN_Z + 2 + (int) Math.floorMod(mix(seed, 2000 + k), (long) (MAX_Z - MIN_Z - 3));
		if (k == FLOOR_COUNT - 1) {
			// the last hole must not come down on top of the End portal room
			int room = portalRoomZ(seed);
			while (Math.abs(z - room) <= PortalRoom.HALF_LENGTH + 3) {
				z = MIN_Z + 2 + Math.floorMod(z - MIN_Z - 2 + 2 * PortalRoom.HALF_LENGTH + 7, MAX_Z - MIN_Z - 3);
			}
		}
		return z;
	}

	/** Centre Z of the End portal room in the bottom layer. */
	public static int portalRoomZ(long seed) {
		return 30 + (int) Math.floorMod(mix(seed, 3000), 241L);
	}

	public static int portalRoomFloorY() {
		return layerBottom(DEEP_LAYERS); // stone-brick floor sits right on the bottom bedrock
	}

	/** Where eyes of ender should fly: the middle of the portal. */
	public static BlockPos portalTarget(long seed) {
		return new BlockPos(PortalRoom.PORTAL_X, portalRoomFloorY() + 2, portalRoomZ(seed));
	}
}
