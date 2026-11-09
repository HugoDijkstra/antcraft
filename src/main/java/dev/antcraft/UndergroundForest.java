package dev.antcraft;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
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

	/** True if (x, z) lies under a deep structure piece (a village) in this layer's forest band. */
	private static boolean inVillage(StripLayout layout, List<BoundingBox> pieces, int layer, int x, int z) {
		return inVillage(layout, pieces, layer, x, z, 6);
	}

	static boolean inVillage(StripLayout layout, List<BoundingBox> pieces, int layer, int x, int z, int margin) {
		int floor = layout.forestFloor(layer);
		for (BoundingBox box : pieces) {
			if (box.maxY() < floor - 12 || box.minY() > floor + layout.forestHeight) continue;
			// wide margin: big trees (2x2 trunks, wide canopies) must not reach into the village
			if (x >= box.minX() - margin && x <= box.maxX() + margin && z >= box.minZ() - margin && z <= box.maxZ() + margin) return true;
		}
		return false;
	}

	private static ResourceKey<Feature> pick(List<LayerSpec.TreeChoice> choices, RandomSource random) {
		int total = 0;
		for (LayerSpec.TreeChoice c : choices) total += c.weight();
		int r = random.nextInt(total);
		for (LayerSpec.TreeChoice c : choices) {
			r -= c.weight();
			if (r < 0) return c.feature();
		}
		return choices.getFirst().feature();
	}

	private static boolean isGround(BlockState state, StripLayout layout) {
		if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD) || state.is(Blocks.MYCELIUM) || state.is(Blocks.DIRT)
			|| state.is(Blocks.SAND)) return true;
		// the grounds of the tall layouts' forests (layout 1 keeps its original set: a mega spruce's
		// podzol, for example, must stay tree-free there)
		return layout.id >= 2 && (state.is(Blocks.WARPED_NYLIUM) || state.is(Blocks.PALE_MOSS_BLOCK) || state.is(Blocks.RED_SAND) || state.is(Blocks.PODZOL)
			|| state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.STONE));
	}

	/** Public view of the forest ground test (for {@link SelfCheck}). */
	public static boolean isGroundBlock(BlockState state, StripLayout layout) {
		return isGround(state, layout);
	}

	/** Finds the forest floor in a column: the ground block with air (or a plant) on top, or null. */
	private static BlockPos findGround(WorldGenLevel level, DeepTerrain terrain, int layer, int x, int z) {
		int surface = terrain.surfaceAt(layer, x, z);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surface, z);
		if (!isGround(level.getBlockState(pos), terrain.layout())) return null;
		BlockState above = level.getBlockState(pos.above());
		if (!above.isAir() && !above.canBeReplaced()) return null;
		return pos.immutable();
	}

	public static void placeTrees(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk, DeepTerrain terrain, List<BoundingBox> deepPieces,
		@org.jspecify.annotations.Nullable VaultPlan vault) {
		ChunkPos pos = chunk.getPos();
		StripBounds bounds = terrain.bounds();
		StripLayout layout = terrain.layout();
		int minX = Math.max(pos.getMinBlockX(), bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		double areaScale = (maxX - minX + 1) * (maxZ - minZ + 1) / 256.0;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(terrain.seed(), 5000), Strip.mix(pos.x(), pos.z()));
		var features = level.registryAccess().lookupOrThrow(Registries.FEATURE);

		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			LayerSpec spec = layout.layer(layer);
			if (!spec.hasForest()) continue;
			if (spec.theme().isDesert()) {
				placeDesertWell(level, generator, terrain, deepPieces, random, layer, minX, maxX, minZ, maxZ);
				continue;
			}
			List<LayerSpec.TreeChoice> choices = spec.trees();
			int attempts = (int) Math.ceil(spec.treeAttempts() * areaScale);
			for (int a = 0; a < attempts; a++) {
				// keep trunks a block away from the walls so canopies mostly stay inside
				int x = Math.clamp(minX + random.nextInt(maxX - minX + 1), bounds.minX() + 1, bounds.maxX() - 1);
				int z = Math.clamp(minZ + random.nextInt(maxZ - minZ + 1), bounds.minZ() + 1, bounds.maxZ() - 1);
				ResourceKey<Feature> key = pick(choices, random);
				if (inVillage(layout, deepPieces, layer, x, z)) continue;
				// keep the fall line from the hole above clear, so digging down lands in the pool
				if (terrain.nearHoleAbove(layer, x, z, 4, vault)) continue;
				BlockPos ground = findGround(level, terrain, layer, x, z);
				if (ground == null) continue;
				// under the sea (no islands yet); tall layouts only, so layout 1 keeps its original placement
				if (layout.id >= 2 && !level.getFluidState(ground.above()).isEmpty()) continue;
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
		WorldGenLevel level, ChunkGenerator generator, DeepTerrain terrain, List<BoundingBox> deepPieces, RandomSource random, int layer, int minX, int maxX, int minZ, int maxZ
	) {
		if (random.nextFloat() > 0.35F) return;
		StripBounds bounds = terrain.bounds();
		int x = Math.clamp(minX + random.nextInt(maxX - minX + 1), bounds.minX() + 3, bounds.maxX() - 3);
		int z = Math.clamp(minZ + random.nextInt(maxZ - minZ + 1), bounds.minZ() + 3, bounds.maxZ() - 3);
		if (inVillage(terrain.layout(), deepPieces, layer, x, z)) return;
		BlockPos ground = findGround(level, terrain, layer, x, z);
		if (ground == null) return;
		level.registryAccess().lookupOrThrow(Registries.FEATURE).get(MiscOverworldFeatures.DESERT_WELL)
			.ifPresent(well -> well.value().place(level, generator, random, ground.above()));
	}

	private static final List<EntityType<? extends Mob>> ANIMALS = List.of(EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CHICKEN);

	/** The tall layout's land animals per theme row (layout 1 only uses FARM, DESERT and MOOSHROOM). */
	private static List<EntityType<? extends Mob>> landAnimals(LayerSpec.Animals animals) {
		return switch (animals) {
			case TAIGA -> List.of(EntityTypes.WOLF, EntityTypes.FOX, EntityTypes.RABBIT);
			case AUTUMN -> List.of(EntityTypes.CHICKEN);
			case BADLANDS -> List.of(EntityTypes.ARMADILLO, EntityTypes.RABBIT);
			case DARKWOOD -> List.of(EntityTypes.PIG, EntityTypes.RABBIT);
			case JUNGLE -> List.of(EntityTypes.PARROT, EntityTypes.OCELOT, EntityTypes.PANDA);
			case SAVANNA -> List.of(EntityTypes.HORSE, EntityTypes.DONKEY);
			case CRAGS -> List.of(EntityTypes.GOAT, EntityTypes.LLAMA);
			case CRYSTAL -> List.of(EntityTypes.BEE, EntityTypes.RABBIT);
			case SWAMP -> List.of(EntityTypes.FROG);
			case FROZEN -> List.of(EntityTypes.POLAR_BEAR, EntityTypes.RABBIT);
			default -> List.of();
		};
	}

	/** Water animals per theme row: in the ponds (lush) or the sea (reef). */
	private static List<EntityType<? extends Mob>> waterAnimals(LayerSpec.Animals animals) {
		return switch (animals) {
			case LUSH -> List.of(EntityTypes.AXOLOTL, EntityTypes.GLOW_SQUID);
			case REEF -> List.of(EntityTypes.TROPICAL_FISH, EntityTypes.NAUTILUS, EntityTypes.GLOW_SQUID);
			default -> List.of();
		};
	}

	/** Chunk of the Autumn Hollow's two sniffers. */
	static ChunkPos snifferChunk(DeepTerrain terrain, int layer) {
		return DeepStarts.projectedChunk(terrain.seed(), terrain.bounds(), terrain.layout(), layer, 9700);
	}

	private static void spawn(WorldGenRegion region, EntityType<? extends Mob> type, double x, double y, double z, RandomSource random) {
		Mob mob = type.create(region.getLevel(), EntitySpawnReason.CHUNK_GENERATION);
		if (mob == null) return;
		mob.snapTo(x, y, z, random.nextFloat() * 360.0F, 0.0F);
		mob.finalizeSpawn(region, region.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.CHUNK_GENERATION, null);
		mob.setPersistenceRequired();
		region.addFreshEntityWithPassengers(mob);
	}

	/** Tall layouts: the theme's animals (land, water, the two sniffers). */
	private static void spawnThemed(WorldGenRegion region, DeepTerrain terrain, int layer, LayerSpec spec, RandomSource random, int minX, int maxX, int minZ, int maxZ) {
		List<EntityType<? extends Mob>> water = waterAnimals(spec.animals());
		if (!water.isEmpty()) {
			EntityType<? extends Mob> type = water.get(random.nextInt(water.size()));
			int count = 1 + random.nextInt(3);
			for (int i = 0; i < count; i++) {
				int x = minX + random.nextInt(maxX - minX + 1), z = minZ + random.nextInt(maxZ - minZ + 1);
				int surface = terrain.surfaceAt(layer, x, z);
				BlockPos pos = spec.theme().isWater() ? new BlockPos(x, surface + 2, z) : new BlockPos(x, surface, z);
				if (!region.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) continue;
				spawn(region, type, x + 0.5, pos.getY(), z + 0.5, random);
			}
			return;
		}
		List<EntityType<? extends Mob>> land = landAnimals(spec.animals());
		if (spec.animals() == LayerSpec.Animals.AUTUMN && region.getCenter().equals(snifferChunk(terrain, layer))) {
			land = List.of(EntityTypes.SNIFFER);
		}
		if (land.isEmpty()) return;
		EntityType<? extends Mob> type = land.get(random.nextInt(land.size()));
		boolean sniffers = type == EntityTypes.SNIFFER;
		int count = sniffers ? 2 : type == EntityTypes.HORSE || type == EntityTypes.DONKEY || type == EntityTypes.POLAR_BEAR || type == EntityTypes.PANDA ? 1 + random.nextInt(2) : 2 + random.nextInt(3);
		for (int i = 0, tries = 0; i < count && tries < 24; tries++) {
			int x = minX + random.nextInt(maxX - minX + 1), z = minZ + random.nextInt(maxZ - minZ + 1);
			BlockPos ground = findGround(region, terrain, layer, x, z);
			if (ground == null || !region.getFluidState(ground.above()).isEmpty() || !hasHeadroom(region, ground, sniffers || type == EntityTypes.HORSE || type == EntityTypes.LLAMA ? 3 : 2)) {
				if (!sniffers) i++;
				continue;
			}
			spawn(region, type, x + 0.5, ground.getY() + 1, z + 0.5, random);
			i++;
		}
	}

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
		StripLayout layout = terrain.layout();

		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			if (!layout.layer(layer).hasForest()) continue;
			boolean sniffers = layout.layer(layer).animals() == LayerSpec.Animals.AUTUMN && pos.equals(snifferChunk(terrain, layer));
			if (random.nextFloat() > 0.6F && !sniffers) continue;
			EntityType<? extends Mob> type = switch (layout.layer(layer).animals()) {
				case MOOSHROOM -> EntityTypes.MOOSHROOM;
				case DESERT -> random.nextFloat() < 0.2F ? EntityTypes.CAMEL : EntityTypes.RABBIT;
				case FARM -> ANIMALS.get(random.nextInt(ANIMALS.size()));
				case NONE -> null;
				default -> {
					spawnThemed(region, terrain, layer, layout.layer(layer), random, minX, maxX, minZ, maxZ);
					yield null;
				}
			};
			if (type == null) continue;
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
