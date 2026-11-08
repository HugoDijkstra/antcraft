package dev.antcraft;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Trees and animals for the forest band in each deep layer. */
public final class UndergroundForest {
	private UndergroundForest() {}

	private record TreeChoice(ResourceKey<Feature> feature, int weight) {}

	private static List<TreeChoice> trees(int layer) {
		return switch (layer) {
			case 1 -> List.of(new TreeChoice(TreeFeatures.OAK, 5), new TreeChoice(TreeFeatures.BIRCH, 3), new TreeChoice(TreeFeatures.FANCY_OAK, 2));
			case 2 -> List.of(new TreeChoice(TreeFeatures.SPRUCE, 6), new TreeChoice(TreeFeatures.PINE, 3), new TreeChoice(TreeFeatures.MEGA_SPRUCE, 1));
			case 3 -> List.of(new TreeChoice(TreeFeatures.DARK_OAK, 8), new TreeChoice(TreeFeatures.OAK, 2));
			case 4 -> List.of(new TreeChoice(TreeFeatures.JUNGLE_TREE, 6), new TreeChoice(TreeFeatures.MEGA_JUNGLE_TREE, 2), new TreeChoice(TreeFeatures.JUNGLE_BUSH, 2));
			case 5 -> List.of(new TreeChoice(TreeFeatures.CHERRY, 1));
			case 6 -> List.of(); // the underground desert
			case 7 -> List.of(new TreeChoice(TreeFeatures.MANGROVE, 3), new TreeChoice(TreeFeatures.TALL_MANGROVE, 1));
			default -> List.of(new TreeChoice(TreeFeatures.HUGE_BROWN_MUSHROOM, 1), new TreeChoice(TreeFeatures.HUGE_RED_MUSHROOM, 1));
		};
	}

	/** Tree attempts per full 16x16 chunk. */
	private static int treeAttempts(int layer) {
		return switch (layer) {
			case 3, 4 -> 14;
			case 6 -> 0;
			case 8 -> 5;
			default -> 9;
		};
	}

	/** True if (x, z) lies under a deep structure piece (a village) in this layer's forest band. */
	private static boolean inVillage(List<BoundingBox> pieces, int layer, int x, int z) {
		int floor = Strip.forestFloor(layer);
		for (BoundingBox box : pieces) {
			if (box.maxY() < floor - 12 || box.minY() > floor + Strip.FOREST_HEIGHT) continue;
			// wide margin: big trees (2x2 trunks, wide canopies) must not reach into the village
			if (x >= box.minX() - 6 && x <= box.maxX() + 6 && z >= box.minZ() - 6 && z <= box.maxZ() + 6) return true;
		}
		return false;
	}

	private static ResourceKey<Feature> pick(List<TreeChoice> choices, RandomSource random) {
		int total = 0;
		for (TreeChoice c : choices) total += c.weight();
		int r = random.nextInt(total);
		for (TreeChoice c : choices) {
			r -= c.weight();
			if (r < 0) return c.feature();
		}
		return choices.getFirst().feature();
	}

	private static boolean isGround(BlockState state) {
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT)
			|| state.is(Blocks.SAND);
	}

	/** Finds the forest floor in a column: the ground block with air (or a plant) on top, or null. */
	private static BlockPos findGround(WorldGenLevel level, DeepTerrain terrain, int layer, int x, int z) {
		int surface = terrain.surfaceAt(layer, x, z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surface, z);
		if (!isGround(level.getBlockState(pos))) return null;
		BlockState above = level.getBlockState(pos.above());
		if (!above.isAir() && !above.canBeReplaced()) return null;
		return pos.immutable();
	}

	public static void placeTrees(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk, DeepTerrain terrain, List<BoundingBox> deepPieces) {
		ChunkPos pos = chunk.getPos();
		StripBounds bounds = terrain.bounds();
		int minX = Math.max(pos.getMinBlockX(), bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		double areaScale = (maxX - minX + 1) * (maxZ - minZ + 1) / 256.0;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(terrain.seed(), 5000), Strip.mix(pos.x(), pos.z()));
		var features = level.registryAccess().lookupOrThrow(Registries.FEATURE);

		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			if (layer == Strip.DESERT_LAYER) {
				placeDesertWell(level, generator, terrain, deepPieces, random, minX, maxX, minZ, maxZ);
				continue;
			}
			List<TreeChoice> choices = trees(layer);
			int attempts = (int) Math.ceil(treeAttempts(layer) * areaScale);
			for (int a = 0; a < attempts; a++) {
				// keep trunks a block away from the walls so canopies mostly stay inside
				int x = Math.clamp(minX + random.nextInt(maxX - minX + 1), bounds.minX() + 1, bounds.maxX() - 1);
				int z = Math.clamp(minZ + random.nextInt(maxZ - minZ + 1), bounds.minZ() + 1, bounds.maxZ() - 1);
				ResourceKey<Feature> key = pick(choices, random);
				if (inVillage(deepPieces, layer, x, z)) continue;
				BlockPos ground = findGround(level, terrain, layer, x, z);
				if (ground == null) continue;
				Holder<Feature> feature = features.get(key).orElse(null);
				if (feature == null) continue;
				BlockPos sapling = ground.above();
				BlockState previous = level.getBlockState(sapling);
				if (!previous.isAir()) level.setBlock(sapling, DeepTerrain.AIR, 2);
				if (!feature.value().place(level, generator, random, sapling) && !previous.isAir()) {
					level.setBlock(sapling, previous, 2);
				}
			}
		}
	}

	/** About one desert well every three chunks of the underground desert. */
	private static void placeDesertWell(
		WorldGenLevel level, ChunkGenerator generator, DeepTerrain terrain, List<BoundingBox> deepPieces, RandomSource random, int minX, int maxX, int minZ, int maxZ
	) {
		if (random.nextFloat() > 0.35F) return;
		StripBounds bounds = terrain.bounds();
		int x = Math.clamp(minX + random.nextInt(maxX - minX + 1), bounds.minX() + 3, bounds.maxX() - 3);
		int z = Math.clamp(minZ + random.nextInt(maxZ - minZ + 1), bounds.minZ() + 3, bounds.maxZ() - 3);
		if (inVillage(deepPieces, Strip.DESERT_LAYER, x, z)) return;
		BlockPos ground = findGround(level, terrain, Strip.DESERT_LAYER, x, z);
		if (ground == null) return;
		level.registryAccess().lookupOrThrow(Registries.FEATURE).get(MiscOverworldFeatures.DESERT_WELL)
			.ifPresent(well -> well.value().place(level, generator, random, ground.above()));
	}

	private static final List<EntityType<? extends Mob>> ANIMALS = List.of(EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CHICKEN);

	/** Air (no fluid, no mushroom cap) for {@code height} blocks above the ground, so the mob can't suffocate. */
	private static boolean hasHeadroom(WorldGenLevel level, BlockPos ground, int height) {
		for (int dy = 1; dy <= height; dy++) {
			BlockState state = level.getBlockState(ground.above(dy));
			if (!state.isAir() && !(dy == 1 && state.canBeReplaced() && state.getFluidState().isEmpty())) return false;
		}
		return true;
	}

	/** Spawns a herd or two of farm animals in each forest. Called once per chunk at generation. */
	public static void spawnAnimals(WorldGenRegion region, DeepTerrain terrain) {
		ChunkPos pos = region.getCenter();
		StripBounds bounds = terrain.bounds();
		int minX = Math.max(pos.getMinBlockX(), bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(terrain.seed(), 6000), Strip.mix(pos.x(), pos.z()));

		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			if (random.nextFloat() > 0.6F) continue;
			EntityType<? extends Mob> type = layer == 8 ? EntityTypes.MOOSHROOM
				: layer == Strip.DESERT_LAYER ? (random.nextFloat() < 0.2F ? EntityTypes.CAMEL : EntityTypes.RABBIT)
				: ANIMALS.get(random.nextInt(ANIMALS.size()));
			int count = type == EntityTypes.CAMEL ? 1 : 2 + random.nextInt(3);
			for (int i = 0; i < count; i++) {
				int x = minX + random.nextInt(maxX - minX + 1);
				int z = minZ + random.nextInt(maxZ - minZ + 1);
				BlockPos ground = findGround(region, terrain, layer, x, z);
				if (ground == null || !hasHeadroom(region, ground, type == EntityTypes.CAMEL ? 3 : 2)) continue;
				Mob mob = type.create(region.getLevel(), EntitySpawnReason.CHUNK_GENERATION);
				if (mob == null) continue;
				mob.snapTo(x + 0.5, ground.getY() + 1, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
				mob.finalizeSpawn(region, region.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.CHUNK_GENERATION, null);
				mob.setPersistenceRequired();
				region.addFreshEntityWithPassengers(mob);
			}
		}
	}
}
