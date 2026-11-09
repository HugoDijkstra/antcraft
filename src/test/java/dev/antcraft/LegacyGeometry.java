package dev.antcraft;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * A frozen copy of the strip geometry and the per-layer tables as they were before layouts
 * existed (the static {@code Strip} constants and methods, {@code DeepTerrain}'s stone and ore
 * arrays, {@code UndergroundForest}'s tree switches and {@code ArenaManager}'s room themes).
 * {@link StripLayout#LAYOUT_1} must reproduce all of it exactly. Never edit this file.
 */
final class LegacyGeometry {
	private LegacyGeometry() {}

	static final int WORLD_MIN_Y = -2032;
	static final int WORLD_MAX_Y = 335;
	static final int VANILLA_MIN_Y = -64;
	static final int BOTTOM_TOP_Y = -2028;
	static final int FLOOR_COUNT = 8;
	static final int FLOOR_SPACING = 256;
	static final int FLOOR_THICKNESS = 3;
	static final int DEEP_LAYERS = FLOOR_COUNT;
	static final int HALL_HEIGHT = 6;
	static final int FOREST_HEIGHT = 40;
	static final int CITY_LAYER = 5;
	static final int SEALED_FLOOR = 4;
	static final int ANCIENT_SEALED_FLOOR = 7;
	static final int[] GUARDIAN_FLOORS = {0, 2, 6};
	static final int DESERT_LAYER = 6;
	static final int CITY_START_Y = -1280;
	static final int CITY_MIN_Y = CITY_START_Y - 40, CITY_MAX_Y = CITY_START_Y + 60;
	static final int PYRAMID_SIZE = 21, PYRAMID_OFFSET_Z = 4;

	static boolean isGuardianFloor(int k) {
		for (int floor : GUARDIAN_FLOORS) {
			if (floor == k) return true;
		}
		return false;
	}

	static int floorBase(int k) {
		return VANILLA_MIN_Y - FLOOR_SPACING * k;
	}

	static int floorTop(int k) {
		return floorBase(k) + FLOOR_THICKNESS - 1;
	}

	static int layerBottom(int layer) {
		return layer < DEEP_LAYERS ? floorTop(layer) + 1 : BOTTOM_TOP_Y + 1;
	}

	static int layerTop(int layer) {
		return floorBase(layer - 1) - 1;
	}

	static int hallBottom(int layer) {
		return layer == 0 ? floorTop(0) + 1 : layerBottom(layer);
	}

	static int forestFloor(int layer) {
		int bottom = layerBottom(layer);
		int top = layerTop(layer);
		return bottom + (int) ((top - bottom) * 0.45);
	}

	static int layerAt(int y) {
		if (y > floorTop(0)) return 0;
		for (int layer = 1; layer <= DEEP_LAYERS; layer++) {
			if (y <= layerTop(layer) && y >= layerBottom(layer)) return layer;
		}
		return -1;
	}

	static int floorAt(int y) {
		for (int k = 0; k < FLOOR_COUNT; k++) {
			if (y >= floorBase(k) && y <= floorTop(k)) return k;
		}
		return -1;
	}

	static ChunkPos cityChunk(StripBounds bounds) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.centerZ() >> 4);
	}

	static final String[] VILLAGE_TYPES = {null, "plains", "taiga", "plains", "savanna", "plains", "desert", "taiga", "snowy"};

	static String villageType(int layer) {
		return VILLAGE_TYPES[layer];
	}

	static ChunkPos villageChunk(long seed, int layer, StripBounds bounds) {
		int firstRow = bounds.minZ() >> 4, lastRow = bounds.maxZ() >> 4;
		int rows = Math.max(1, lastRow - firstRow - 1);
		int base = (int) Math.floorMod(Strip.mix(seed, 7000), (long) rows);
		int chunkX = layer % 2 == 0 ? bounds.minX() >> 4 : bounds.maxX() >> 4;
		return new ChunkPos(chunkX, firstRow + 1 + Math.floorMod(base + layer * villageStep(rows), rows));
	}

	static ChunkPos pyramidChunk(long seed, StripBounds bounds) {
		int firstRow = (bounds.minZ() + 1 - PYRAMID_OFFSET_Z + 15) >> 4;
		int lastRow = (bounds.maxZ() - 1 - PYRAMID_OFFSET_Z - (PYRAMID_SIZE - 1)) >> 4;
		if (lastRow < firstRow) lastRow = firstRow;
		int rows = lastRow - firstRow + 1;
		int villageRow = villageChunk(seed, DESERT_LAYER, bounds).z();
		int holeZ = holeZ(seed, DESERT_LAYER - 1, bounds);
		int startIndex = (int) Math.floorMod(Strip.mix(seed, 8000), (long) rows);
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

	static int holeX(long seed, int k, StripBounds bounds) {
		return bounds.minX() + 2 + (int) Math.floorMod(Strip.mix(seed, 1000 + k), (long) (bounds.width() - 4));
	}

	static int holeZ(long seed, int k, StripBounds bounds) {
		int range = bounds.length() - 4;
		int z = bounds.minZ() + 2 + (int) Math.floorMod(Strip.mix(seed, 2000 + k), (long) range);
		if (k == FLOOR_COUNT - 1) {
			int room = portalRoomZ(seed, bounds);
			while (Math.abs(z - room) <= PortalRoom.HALF_LENGTH + 3) {
				z = bounds.minZ() + 2 + Math.floorMod(z - bounds.minZ() - 2 + 2 * PortalRoom.HALF_LENGTH + 7, range);
			}
		}
		return z;
	}

	static int portalRoomZ(long seed, StripBounds bounds) {
		return bounds.minZ() + 30 + (int) Math.floorMod(Strip.mix(seed, 3000), (long) (bounds.length() - 59));
	}

	static int portalRoomFloorY() {
		return layerBottom(DEEP_LAYERS);
	}

	static BlockPos portalTarget(long seed, StripBounds bounds) {
		return new BlockPos(PortalRoom.portalX(bounds), portalRoomFloorY() + 2, portalRoomZ(seed, bounds));
	}

	/** Mining hardness factor of the old code, {@code 1 + perLayer * layer}. */
	static float hardness(int layer, double perLayer) {
		if (layer <= 0) return 1.0F;
		return 1.0F + (float) perLayer * layer;
	}

	// ------------------------------------------------------------------ tables (need a bootstrapped game)

	static final class Tables {
		private Tables() {}

		static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

		static final BlockState[] BASE_STONE = {
			DEEPSLATE, DEEPSLATE, DEEPSLATE, Blocks.TUFF.defaultBlockState(), Blocks.DRIPSTONE_BLOCK.defaultBlockState(), DEEPSLATE,
			Blocks.SANDSTONE.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState(), DEEPSLATE,
		};
		static final BlockState[] PATCH_STONE = {
			DEEPSLATE, Blocks.TUFF.defaultBlockState(), Blocks.TUFF.defaultBlockState(), Blocks.CALCITE.defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
			Blocks.SCULK.defaultBlockState(), Blocks.RED_SANDSTONE.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState(),
		};

		/** stoneAt's patch threshold. */
		static double patchThreshold(int layer) {
			return layer == 7 ? 0.62 : 0.4;
		}

		/** pondValue's threshold. */
		static double pondThreshold(int layer) {
			return layer == 7 ? 0.25 : layer == DESERT_LAYER ? 0.66 : 0.5;
		}

		static BlockState pondBottom(int layer) {
			return layer == DESERT_LAYER ? Blocks.SAND.defaultBlockState() : Blocks.CLAY.defaultBlockState();
		}

		static BlockState light(int layer) {
			return layer == 8 ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.GLOWSTONE.defaultBlockState();
		}

		/** True if the layer's puddles may be water (else lava). */
		static boolean waterPuddles(int layer) {
			return layer <= 2;
		}

		record Ore(BlockState stone, BlockState deepslate, int minLayer, int maxLayer, int veins, int perLayer, int size) {}

		private static Ore ore(Block stone, Block deepslate, int minLayer, int maxLayer, int veins, int perLayer, int size) {
			return new Ore(stone.defaultBlockState(), deepslate.defaultBlockState(), minLayer, maxLayer, veins, perLayer, size);
		}

		static final List<Ore> ORES = List.of(
			ore(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 1, 8, 24, -1, 14),
			ore(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 1, 8, 10, 0, 10),
			ore(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 2, 8, 9, 0, 9),
			ore(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 3, 8, 6, 0, 8),
			ore(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 4, 8, 5, 0, 8),
			ore(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 4, 8, 4, 0, 7),
			ore(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 5, 8, 3, 0, 6),
			ore(Blocks.OBSIDIAN, Blocks.OBSIDIAN, 6, 8, 2, 0, 6),
			ore(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 6, 8, 2, 0, 3),
			ore(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 7, 8, 1, 0, 2)
		);

		record TreeChoice(ResourceKey<Feature> feature, int weight) {}

		static List<TreeChoice> trees(int layer) {
			return switch (layer) {
				case 1 -> List.of(new TreeChoice(TreeFeatures.OAK, 5), new TreeChoice(TreeFeatures.BIRCH, 3), new TreeChoice(TreeFeatures.FANCY_OAK, 2));
				case 2 -> List.of(new TreeChoice(TreeFeatures.SPRUCE, 6), new TreeChoice(TreeFeatures.PINE, 3), new TreeChoice(TreeFeatures.MEGA_SPRUCE, 1));
				case 3 -> List.of(new TreeChoice(TreeFeatures.DARK_OAK, 8), new TreeChoice(TreeFeatures.OAK, 2));
				case 4 -> List.of(new TreeChoice(TreeFeatures.JUNGLE_TREE, 6), new TreeChoice(TreeFeatures.MEGA_JUNGLE_TREE, 2), new TreeChoice(TreeFeatures.JUNGLE_BUSH, 2));
				case 5 -> List.of(new TreeChoice(TreeFeatures.CHERRY, 1));
				case 6 -> List.of();
				case 7 -> List.of(new TreeChoice(TreeFeatures.MANGROVE, 3), new TreeChoice(TreeFeatures.TALL_MANGROVE, 1));
				default -> List.of(new TreeChoice(TreeFeatures.HUGE_BROWN_MUSHROOM, 1), new TreeChoice(TreeFeatures.HUGE_RED_MUSHROOM, 1));
			};
		}

		static int treeAttempts(int layer) {
			return switch (layer) {
				case 3, 4 -> 14;
				case 6 -> 0;
				case 8 -> 5;
				default -> 9;
			};
		}

		record Theme(BlockState pillar, BlockState accentPillar, BlockState carpet, BlockState banner, BlockState lantern, BlockState accent) {}

		static Theme theme(int floor) {
			return switch (floor) {
				case 0 -> new Theme(Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState(), Blocks.CARPET.pick(DyeColor.RED).defaultBlockState(),
					Blocks.WALL_BANNER.pick(DyeColor.GRAY).defaultBlockState(), Blocks.LANTERN.defaultBlockState(), Blocks.BARREL.defaultBlockState());
				case 2 -> new Theme(Blocks.BONE_BLOCK.defaultBlockState(), Blocks.BONE_BLOCK.defaultBlockState(), Blocks.CARPET.pick(DyeColor.BROWN).defaultBlockState(),
					Blocks.WALL_BANNER.pick(DyeColor.BROWN).defaultBlockState(), Blocks.SOUL_LANTERN.defaultBlockState(), Blocks.SKELETON_SKULL.defaultBlockState());
				default -> new Theme(Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CHISELED_SANDSTONE.defaultBlockState(), Blocks.CARPET.pick(DyeColor.ORANGE).defaultBlockState(),
					Blocks.WALL_BANNER.pick(DyeColor.ORANGE).defaultBlockState(), Blocks.LANTERN.defaultBlockState(), Blocks.DECORATED_POT.defaultBlockState());
			};
		}

		/** Old guardian table: type name, health, scale (case 0 / 2 / 6). */
		static final Object[][] GUARDIANS = {
			{0, "Tunnel Captain", 60.0, 1.0},
			{2, "Deep Beast", 180.0, 1.0},
			{6, "Desert Colossus", 200.0, 2.5},
		};
	}
}
