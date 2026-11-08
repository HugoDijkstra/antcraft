package dev.antcraft;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import dev.antcraft.mixin.ScatteredFeaturePieceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * Overworld generator for AntCraft. Vanilla terrain (y -64..320) comes from the wrapped
 * {@link NoiseBasedChunkGenerator}; this class cuts it down to the strip ({@link StripBounds}, 20x300
 * by default, stored per world), adds the walls,
 * and generates everything below -64 via {@link DeepTerrain}.
 *
 * <p>Walls: invisible barrier above ground, bedrock below it. Barrier lets skylight through, so a
 * full-height barrier wall would light up every deep layer from the side.
 */
public final class StripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator {
	public static final MapCodec<StripChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.base),
				StripBounds.SIZE_CODEC.forGetter(g -> g.bounds)
			)
			.apply(i, StripChunkGenerator::new)
	);

	public static final ResourceKey<Structure> DEEP_CITY = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "deep_ancient_city")
	);

	public static final ResourceKey<Structure> DEEP_PYRAMID = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "deep_desert_pyramid")
	);

	/**
	 * While a deep village is being laid out, the jigsaw code asks for the "surface" height to put
	 * its roads and houses on. That must be the floor of the underground forest, not the real
	 * surface far above.
	 */
	private static final ThreadLocal<Integer> DEEP_GROUND = new ThreadLocal<>();

	/** True while a deep structure (village) is being started by {@link #startDeepStructure}; its pieces are shifted to the strip's centre x afterwards. */
	public static boolean generatingDeep() {
		return DEEP_GROUND.get() != null;
	}

	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;
	/** Per wall column: lowest y that is barrier (everything below is bedrock). */
	private final Map<Long, Integer> wallSplit = new ConcurrentHashMap<>();

	public StripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds) {
		super(base.getBiomeSource());
		this.base = base;
		this.bounds = bounds;
	}

	@Override
	public NoiseBasedChunkGenerator base() {
		return this.base;
	}

	@Override
	public StripBounds bounds() {
		return this.bounds;
	}

	private DeepTerrain terrain(long seed) {
		return DeepTerrain.forSeed(seed, this.bounds);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	// ------------------------------------------------------------------ walls

	private int wallSplitY(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		return this.wallSplit.computeIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL), k -> {
			// clamp the wall column to the nearest strip column so the split follows the strip's terrain
			int sx = this.bounds.clampX(x), sz = this.bounds.clampZ(z);
			int surface = this.base.getBaseHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
			return Math.max(surface - 1, Strip.VANILLA_MIN_Y);
		});
	}


	// ------------------------------------------------------------------ biomes

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
		if (!this.bounds.chunkTouches(chunk.getPos())) {
			return CompletableFuture.completedFuture(chunk); // void: default biome is fine, skip the climate noise
		}
		return super.createBiomes(randomState, blender, structureManager, chunk);
	}

	// ------------------------------------------------------------------ terrain

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(
		ChunkAccess chunk,
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		BiomeManager biomeManager,
		@Nullable WorldGenRegion carverBiomeRegion,
		Set<Holder<Biome>> possibleBiomes
	) {
		ChunkPos pos = chunk.getPos();
		if (!this.bounds.chunkTouches(pos)) {
			return CompletableFuture.completedFuture(chunk); // pure void
		}
		CompletableFuture<ChunkAccess> vanilla = this.bounds.chunkOverlaps(pos)
			? this.base.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
			: CompletableFuture.completedFuture(chunk);
		List<BoundingBox> cityPieces = findDeepPieces(structureManager, pos);
		return vanilla.thenApply(c -> {
			this.shapeChunk(c, this.terrain(randomState.seed()), cityPieces, randomState);
			return c;
		});
	}

	/** Boxes of deep structure pieces (ancient city, deep villages) that come near this chunk. */
	private static List<BoundingBox> findDeepPieces(StructureManager structureManager, ChunkPos pos) {
		List<BoundingBox> boxes = new ArrayList<>();
		BoundingBox chunkBox = new BoundingBox(pos.getMinBlockX() - 4, Strip.WORLD_MIN_Y, pos.getMinBlockZ() - 4, pos.getMaxBlockX() + 4, Strip.WORLD_MAX_Y, pos.getMaxBlockZ() + 4);
		for (StructureStart start : structureManager.startsForStructure(pos.x(), pos.z(), s -> true)) {
			if (start.getBoundingBox().maxY() >= Strip.VANILLA_MIN_Y) continue;
			for (StructurePiece piece : start.getPieces()) {
				if (piece.getBoundingBox().intersects(chunkBox)) boxes.add(piece.getBoundingBox());
			}
		}
		return boxes;
	}

	private void shapeChunk(ChunkAccess chunk, DeepTerrain terrain, List<BoundingBox> cityPieces, RandomState randomState) {
		ChunkPos pos = chunk.getPos();
		LevelHeightAccessor heightAccessor = chunk.getHeightAccessorForGeneration();
		LevelChunkSection[] sections = chunk.getSections();
		for (LevelChunkSection section : sections) section.acquire();
		try {
			for (int lx = 0; lx < 16; lx++) {
				for (int lz = 0; lz < 16; lz++) {
					int x = pos.getMinBlockX() + lx;
					int z = pos.getMinBlockZ() + lz;
					if (this.bounds.inside(x, z)) {
						terrain.reshapeVanillaBottom(chunk, lx, lz, x, z);
						terrain.fillColumn(chunk, lx, lz, x, z, cityPieces);
					} else {
						clearColumnRaw(chunk, lx, lz);
						if (this.bounds.isWall(x, z)) {
							int split = this.wallSplitY(x, z, heightAccessor, randomState);
							for (int y = Strip.WORLD_MIN_Y; y <= Strip.WORLD_MAX_Y; y++) {
								DeepTerrain.set(chunk, lx, y, lz, y < split ? DeepTerrain.BEDROCK : DeepTerrain.BARRIER);
							}
						}
					}
				}
			}
			if (this.bounds.chunkOverlaps(pos)) {
				terrain.placeOres(chunk);
				PortalRoom.build(chunk, terrain.seed(), this.bounds);
				terrain.cutHoles(chunk);
			}
		} finally {
			for (LevelChunkSection section : sections) section.release();
		}
		this.fixHeightmaps(chunk);
	}

	/** Removes everything in a column during terrain generation (before lighting). Skips all-air sections. */
	private static void clearColumnRaw(ChunkAccess chunk, int lx, int lz) {
		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir()) continue;
			for (int ly = 0; ly < 16; ly++) {
				BlockState state = section.getBlockState(lx, ly, lz);
				if (!state.isAir()) {
					if (state.hasBlockEntity()) {
						chunk.removeBlockEntity(new BlockPos(chunk.getPos().getMinBlockX() + lx, minY + i * 16 + ly, chunk.getPos().getMinBlockZ() + lz));
					}
					section.setBlockState(lx, ly, lz, DeepTerrain.AIR, false);
				}
			}
		}
	}

	/** Re-derives the world-gen heightmaps for columns we rewrote (walls and cleared columns). */
	private void fixHeightmaps(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		for (Heightmap.Types type : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG)) {
			Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
			for (int lx = 0; lx < 16; lx++) {
				for (int lz = 0; lz < 16; lz++) {
					int x = pos.getMinBlockX() + lx, z = pos.getMinBlockZ() + lz;
					if (this.bounds.inside(x, z)) continue;
					if (this.bounds.isWall(x, z)) {
						heightmap.update(lx, Strip.WORLD_MAX_Y, lz, DeepTerrain.BARRIER);
					} else {
						int first = heightmap.getFirstAvailable(lx, lz);
						if (first > chunk.getMinY()) heightmap.update(lx, first - 1, lz, DeepTerrain.AIR);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ clipping after decoration

	/** y ranges where decoration can place blocks: the vanilla world, each forest band, the deep city. */
	private static final int[][] SPILL_RANGES = spillRanges();

	private static int[][] spillRanges() {
		int[][] ranges = new int[Strip.DEEP_LAYERS + 2][];
		ranges[0] = new int[] {Strip.VANILLA_MIN_Y, Strip.WORLD_MAX_Y};
		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			int floor = Strip.forestFloor(layer);
			ranges[layer] = new int[] {floor - 8, floor + Strip.FOREST_HEIGHT + 8};
		}
		ranges[Strip.DEEP_LAYERS + 1] = new int[] {Strip.CITY_MIN_Y, Strip.CITY_MAX_Y};
		return ranges;
	}

	/**
	 * After decorating a strip chunk: clears whatever features, trees or structures spilled outside
	 * the strip in the 3x3 chunk region the decoration may write to, and restores any wall block they
	 * replaced. Writes go through {@link ChunkAccess#setBlockState} so light and heightmaps stay
	 * correct even if a neighbour is already light-initialised.
	 */
	private void clipRegion(WorldGenLevel level, ChunkPos center) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = center.x() - 1; cx <= center.x() + 1; cx++) {
			for (int cz = center.z() - 1; cz <= center.z() + 1; cz++) {
				ChunkPos chunkPos = new ChunkPos(cx, cz);
				ChunkAccess chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				int minY = chunk.getMinY();
				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						int x = chunkPos.getMinBlockX() + lx, z = chunkPos.getMinBlockZ() + lz;
						if (this.bounds.inside(x, z)) continue;
						boolean wall = this.bounds.isWall(x, z);
						int split = wall ? this.wallSplitY(x, z, chunk.getHeightAccessorForGeneration(), randomState) : Integer.MIN_VALUE;
						for (int[] range : SPILL_RANGES) {
							for (int i = 0; i < sections.length; i++) {
								int sectionMinY = minY + i * 16;
								if (sectionMinY + 15 < range[0] || sectionMinY > range[1]) continue;
								LevelChunkSection section = sections[i];
								if (!wall && section.hasOnlyAir()) continue;
								for (int ly = 0; ly < 16; ly++) {
									int y = sectionMinY + ly;
									if (y < range[0] || y > range[1]) continue;
									BlockState current = section.getBlockState(lx, ly, lz);
									BlockState wanted = !wall ? DeepTerrain.AIR : y < split ? DeepTerrain.BEDROCK : DeepTerrain.BARRIER;
									if (current == wanted) continue;
									pos.set(x, y, z);
									if (current.hasBlockEntity()) chunk.removeBlockEntity(pos);
									chunk.setBlockState(pos, wanted);
								}
							}
						}
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ decoration, structures, mobs

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		ChunkPos pos = chunk.getPos();
		if (!this.bounds.chunkOverlaps(pos)) return;
		super.applyBiomeDecoration(level, chunk, structureManager);
		DeepTerrain terrain = this.terrain(level.getSeed());
		terrain.sealAboveFirstHall(level, pos);
		List<BoundingBox> deepPieces = findDeepPieces(structureManager, pos);
		UndergroundForest.placeTrees(level, this, chunk, terrain, deepPieces);
		terrain.decorateForests(level, chunk, deepPieces); // plants and lights after trees, so they don't block them
		PortalRoom.placeBlockEntities(level, chunk, level.getSeed(), this.bounds);
		if (pos.equals(Strip.cityChunk(this.bounds))) this.placeCityKeyChest(level, pos, deepPieces);
		this.clipRegion(level, pos);
		terrain.recomputeLeaves(level, pos);
	}

	/**
	 * One guaranteed ancient city chest (with the Ancient Key, see the loot hook in {@link AntCraft})
	 * inside the strip: on the lowest free floor of a city piece in the city's start chunk, after
	 * that chunk's pieces were placed.
	 */
	private void placeCityKeyChest(WorldGenLevel level, ChunkPos pos, List<BoundingBox> deepPieces) {
		int minX = Math.max(pos.getMinBlockX(), this.bounds.minX() + 1), maxX = Math.min(pos.getMaxBlockX(), this.bounds.maxX() - 1);
		int minZ = Math.max(pos.getMinBlockZ(), this.bounds.minZ() + 1), maxZ = Math.min(pos.getMaxBlockZ(), this.bounds.maxZ() - 1);
		int midX = (minX + maxX) / 2, midZ = (minZ + maxZ) / 2;
		BlockPos best = null;
		long bestScore = Long.MAX_VALUE;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (BoundingBox box : deepPieces) {
			if (box.minY() < Strip.CITY_MIN_Y - 10 || box.maxY() > Strip.CITY_MAX_Y) continue; // a village, not the city
			for (int x = Math.max(minX, box.minX()); x <= Math.min(maxX, box.maxX()); x++) {
				for (int z = Math.max(minZ, box.minZ()); z <= Math.min(maxZ, box.maxZ()); z++) {
					for (int y = box.minY() + 1; y < box.maxY() - 1; y++) {
						if (!level.getBlockState(at.set(x, y, z)).isAir() || !level.getBlockState(at.set(x, y + 1, z)).isAir()) continue;
						BlockState below = level.getBlockState(at.set(x, y - 1, z));
						if (!below.isFaceSturdy(level, at, net.minecraft.core.Direction.UP) || !below.getFluidState().isEmpty()) continue;
						long score = (long) (y - Strip.CITY_MIN_Y) * 10000 + Math.abs(x - midX) + Math.abs(z - midZ);
						if (score < bestScore) {
							bestScore = score;
							best = new BlockPos(x, y, z);
						}
						break; // lowest free floor in this column
					}
				}
			}
		}
		if (best == null) {
			AntCraft.LOGGER.warn("AntCraft: no spot for the ancient city key chest in chunk {}", pos);
			return;
		}
		level.setBlock(best, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
		net.minecraft.world.RandomizableContainer.setBlockEntityLootTable(
			level, net.minecraft.util.RandomSource.create(Strip.mix(level.getSeed(), 5001)), best, net.minecraft.world.level.storage.loot.BuiltInLootTables.ANCIENT_CITY
		);
		AntCraft.LOGGER.info("AntCraft: ancient city key chest at {}", best);
	}

	@Override
	public void createStructures(
		RegistryAccess registryAccess,
		ChunkGeneratorStructureState state,
		StructureManager structureManager,
		ChunkAccess centerChunk,
		StructureTemplateManager structureTemplateManager,
		ResourceKey<Level> level
	) {
		// only let structures start inside the strip
		if (!this.bounds.chunkOverlaps(centerChunk.getPos())) return;
		super.createStructures(registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);

		// Vanilla structures must stay above the first floor's hall. With the deeper world, jigsaw
		// structures (trial chambers) can reach lower than in vanilla, and underground ruined portals
		// sink to the world bottom; drop those.
		int lowest = Strip.hallBottom(0) + Strip.HALL_HEIGHT + 1;
		for (Map.Entry<Structure, StructureStart> entry : List.copyOf(centerChunk.getAllStarts().entrySet())) {
			StructureStart start = entry.getValue();
			if (start.isValid() && start.getBoundingBox().minY() < lowest) {
				centerChunk.setStartForStructure(entry.getKey(), StructureStart.INVALID_START);
			}
		}

		if (level != Level.OVERWORLD) return;
		if (centerChunk.getPos().equals(Strip.cityChunk(this.bounds))) {
			this.startDeepStructure(DEEP_CITY, null, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
		}
		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			if (centerChunk.getPos().equals(Strip.villageChunk(state.getLevelSeed(), layer, this.bounds))) {
				ResourceKey<Structure> village = ResourceKey.create(
					Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "deep_village_" + Strip.villageType(layer))
				);
				int ground = Strip.forestFloor(layer) + 1; // first free block above the flattened forest floor
				this.startDeepStructure(village, ground, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
			}
		}
		if (centerChunk.getPos().equals(Strip.pyramidChunk(state.getLevelSeed(), this.bounds))) {
			this.startDeepPyramid(registryAccess, state.getLevelSeed(), structureManager, centerChunk);
		}
	}

	/**
	 * The desert pyramid of the underground desert. A single hand-made piece: built facing north or
	 * south (so its entrance opens along the strip), centred in the strip's width, sunk one block
	 * into the forest floor like vanilla does on the surface. Its {@code heightPosition} is set so
	 * the piece is not projected onto the real surface when it is placed. The forest floor under it
	 * is flattened and kept free of trees like under the deep villages (it is a deep structure piece).
	 */
	private void startDeepPyramid(RegistryAccess registryAccess, long seed, StructureManager structureManager, ChunkAccess chunk) {
		Holder<Structure> holder = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(DEEP_PYRAMID).orElse(null);
		if (holder == null) {
			AntCraft.LOGGER.error("AntCraft: structure {} is missing", DEEP_PYRAMID.identifier());
			return;
		}
		Structure structure = holder.value();
		ChunkPos pos = chunk.getPos();
		int west = this.bounds.centerX() - Strip.PYRAMID_SIZE / 2;
		int north = pos.getMinBlockZ() + Strip.PYRAMID_OFFSET_Z;
		net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(Strip.mix(seed, 8001));
		DesertPyramidPiece piece = new DesertPyramidPiece(random, west, north);
		for (int i = 0; i < 64 && piece.getOrientation() != Direction.NORTH && piece.getOrientation() != Direction.SOUTH; i++) {
			piece = new DesertPyramidPiece(random, west, north);
		}
		piece.move(0, Strip.forestFloor(Strip.DESERT_LAYER) - piece.getBoundingBox().minY(), 0);
		((ScatteredFeaturePieceAccessor) piece).antcraft$setHeightPosition(0);
		StructureStart previous = structureManager.getStartForStructure(structure, chunk);
		StructureStart start = new StructureStart(structure, pos, previous != null ? previous.getReferences() : 0, new PiecesContainer(List.of(piece)));
		structureManager.setStartForStructure(structure, start, chunk);
		AntCraft.LOGGER.info("AntCraft: {} placed facing {}, bounds {}", DEEP_PYRAMID.identifier(), piece.getOrientation(), start.getBoundingBox());
	}

	/**
	 * Starts one of our deep structures in this chunk. They have no structure sets, so the game would
	 * never place them on its own. {@code ground} (if set) is reported as the surface height while
	 * the structure is laid out.
	 */
	private void startDeepStructure(
		ResourceKey<Structure> key,
		@Nullable Integer ground,
		RegistryAccess registryAccess,
		ChunkGeneratorStructureState state,
		StructureManager structureManager,
		ChunkAccess chunk,
		StructureTemplateManager structureTemplateManager,
		ResourceKey<Level> level
	) {
		Holder<Structure> holder = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(key).orElse(null);
		if (holder == null) {
			AntCraft.LOGGER.error("AntCraft: structure {} is missing", key.identifier());
			return;
		}
		Structure structure = holder.value();
		StructureStart previous = structureManager.getStartForStructure(structure, chunk);
		RandomState randomState = state.randomState();
		DEEP_GROUND.set(ground);
		StructureStart start;
		try {
			start = structure.generate(
			holder,
			level,
			registryAccess,
			this,
			this.biomeSource,
			randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED),
			randomState,
			structureTemplateManager,
			state.getLevelSeed(),
			chunk.getPos(),
			previous != null ? previous.getReferences() : 0,
			chunk,
			biome -> true
		);
		} finally {
			DEEP_GROUND.remove();
		}
		if (start.isValid() && ground != null) {
			// The jigsaw start sits at the chunk corner and can rotate away from the narrow strip.
			// Shift the whole village sideways so its centre piece is in the middle of the strip.
			BoundingBox centre = start.getPieces().getFirst().getBoundingBox();
			int dx = this.bounds.centerX() - (centre.minX() + centre.maxX()) / 2;
			for (StructurePiece piece : start.getPieces()) piece.move(dx, 0, 0);
		}
		if (start.isValid()) {
			structureManager.setStartForStructure(structure, start, chunk);
			AntCraft.LOGGER.info("AntCraft: {} placed, bounds {}", key.identifier(), start.getBoundingBox());
		} else {
			AntCraft.LOGGER.warn("AntCraft: {} failed to generate", key.identifier());
		}
	}

	/**
	 * /locate and cartographer maps search outward for a structure. Almost every candidate lies
	 * outside the strip, where nothing generates, so a full search would create thousands of empty
	 * chunks. Keep the search near the strip.
	 */
	@Override
	public com.mojang.datafixers.util.@Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(
		net.minecraft.server.level.ServerLevel level, net.minecraft.core.HolderSet<Structure> wanted, BlockPos pos, int maxSearchRadius, boolean createReference
	) {
		return super.findNearestMapStructure(level, wanted, pos, Math.min(maxSearchRadius, 3), createReference);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		if (!this.bounds.chunkOverlaps(region.getCenter())) return;
		this.base.spawnOriginalMobs(region);
		UndergroundForest.spawnAnimals(region, this.terrain(region.getSeed()));
	}

	/** Spawn search starts in the middle of the strip. */
	@Override
	public ChunkPos getOrigin(RandomState randomState) {
		return new ChunkPos(this.bounds.centerX() >> 4, this.bounds.centerZ() >> 4);
	}

	// ------------------------------------------------------------------ plain delegation

	@Override
	public int getGenDepth() {
		return this.base.getGenDepth();
	}

	@Override
	public int getSeaLevel() {
		return this.base.getSeaLevel();
	}

	@Override
	public int getMinY() {
		return this.base.getMinY();
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		Integer ground = DEEP_GROUND.get();
		if (ground != null) return ground;
		return this.base.getBaseHeight(x, z, type, heightAccessor, randomState);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		return this.base.getBaseColumn(x, z, heightAccessor, randomState);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		this.base.addDebugScreenInfo(result, randomState, feetPos, samplerContext);
		int layer = Strip.layerAt(feetPos.getY());
		result.add("AntCraft layer: " + (layer < 0 ? "bedrock" : layer));
	}
}
