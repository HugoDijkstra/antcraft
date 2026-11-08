package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * The Nether as a strip with the same footprint as the overworld strip (portals map 1:1). Vanilla Nether
 * terrain inside, invisible barrier walls, void outside, a guaranteed fortress and the Nether Key
 * shrine.
 */
public final class NetherStripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator {
	/**
	 * Where the fortress, the key shrine and the bastion go. Saved with the world: worlds from
	 * before the bastion have no {@code layout} field and keep layout 1 (fortress 2/3 along the
	 * strip, shrine at 1/6, no bastion); the antcraft:strip preset asks for the latest layout.
	 */
	public static final int LATEST_LAYOUT = 2;

	public static final MapCodec<NetherStripChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.base),
				StripBounds.SIZE_CODEC.forGetter(g -> g.bounds),
				Codec.intRange(1, LATEST_LAYOUT).optionalFieldOf("layout", 1).forGetter(g -> g.layout)
			)
			.apply(i, NetherStripChunkGenerator::new)
	);

	public static final ResourceKey<Structure> STRIP_FORTRESS = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "strip_fortress")
	);
	public static final ResourceKey<Structure> STRIP_BASTION = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "strip_bastion")
	);
	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;
	private final int layout;

	public NetherStripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds, int layout) {
		super(base.getBiomeSource());
		this.base = base;
		this.bounds = bounds;
		this.layout = layout;
	}

	public int layout() {
		return this.layout;
	}

	@Override
	public NoiseBasedChunkGenerator base() {
		return this.base;
	}

	@Override
	public StripBounds bounds() {
		return this.bounds;
	}

	/**
	 * The guaranteed fortress starts here, in the middle of the strip's width: 5/6 along it (chunk
	 * 0, 15 for 20x300), or 2/3 along it (chunk 0, 12) in layout 1.
	 */
	public static ChunkPos fortressChunk(StripBounds bounds, int layout) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.scaledZ(layout >= 2 ? 250 : 200) >> 4);
	}

	/** The treasure bastion starts here (layout 2+): chunk 0, 2 for 20x300. Null if there is none. */
	public static @Nullable ChunkPos bastionChunk(StripBounds bounds, int layout) {
		return layout >= 2 ? new ChunkPos(bounds.centerX() >> 4, bounds.scaledZ(40) >> 4) : null;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
		if (!this.bounds.chunkTouches(chunk.getPos())) return CompletableFuture.completedFuture(chunk);
		return this.base.createBiomes(randomState, blender, structureManager, chunk);
	}

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
		if (!this.bounds.chunkTouches(pos)) return CompletableFuture.completedFuture(chunk);
		CompletableFuture<ChunkAccess> vanilla = this.bounds.chunkOverlaps(pos)
			? this.base.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
			: CompletableFuture.completedFuture(chunk);
		return vanilla.thenApply(c -> {
			shapeChunk(c, this.bounds);
			return c;
		});
	}

	/** Before decoration: void outside the strip, barrier columns on the wall ring (shared with the End strip). */
	static void shapeChunk(ChunkAccess chunk, StripBounds bounds) {
		ChunkPos pos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		for (LevelChunkSection section : sections) section.acquire();
		try {
			int minY = chunk.getMinY(), maxY = chunk.getMaxY();
			for (int lx = 0; lx < 16; lx++) {
				for (int lz = 0; lz < 16; lz++) {
					int x = pos.getMinBlockX() + lx, z = pos.getMinBlockZ() + lz;
					if (bounds.inside(x, z)) continue;
					boolean wall = bounds.isWall(x, z);
					for (int i = 0; i < sections.length; i++) {
						LevelChunkSection section = sections[i];
						if (!wall && section.hasOnlyAir()) continue;
						for (int ly = 0; ly < 16; ly++) {
							BlockState state = section.getBlockState(lx, ly, lz);
							BlockState wanted = wall ? DeepTerrain.BARRIER : DeepTerrain.AIR;
							if (state == wanted) continue;
							if (state.hasBlockEntity()) chunk.removeBlockEntity(new BlockPos(x, minY + i * 16 + ly, z));
							section.setBlockState(lx, ly, lz, wanted, false);
						}
					}
				}
			}
			// heightmaps for the columns we rewrote
			for (Heightmap.Types type : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG)) {
				Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						int x = pos.getMinBlockX() + lx, z = pos.getMinBlockZ() + lz;
						if (bounds.inside(x, z)) continue;
						if (bounds.isWall(x, z)) {
							heightmap.update(lx, maxY, lz, DeepTerrain.BARRIER);
						} else {
							int first = heightmap.getFirstAvailable(lx, lz);
							if (first > minY) heightmap.update(lx, first - 1, lz, DeepTerrain.AIR);
						}
					}
				}
			}
		} finally {
			for (LevelChunkSection section : sections) section.release();
		}
	}

	/** After decoration: clear spill-over outside the strip and restore wall blocks in the 3x3 region. */
	static void clipRegion(WorldGenLevel level, ChunkPos center, StripBounds bounds) {
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
						if (bounds.inside(x, z)) continue;
						boolean wall = bounds.isWall(x, z);
						BlockState wanted = wall ? DeepTerrain.BARRIER : DeepTerrain.AIR;
						for (int i = 0; i < sections.length; i++) {
							LevelChunkSection section = sections[i];
							if (!wall && section.hasOnlyAir()) continue;
							for (int ly = 0; ly < 16; ly++) {
								BlockState current = section.getBlockState(lx, ly, lz);
								if (current == wanted) continue;
								pos.set(x, minY + i * 16 + ly, z);
								if (current.hasBlockEntity()) chunk.removeBlockEntity(pos);
								chunk.setBlockState(pos, wanted);
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		ChunkPos pos = chunk.getPos();
		if (!this.bounds.chunkOverlaps(pos)) return;
		super.applyBiomeDecoration(level, chunk, structureManager);
		KeyShrine.rebuild(level, pos, this.bounds, this.layout); // after this chunk's fortress pieces and features
		clipRegion(level, pos, this.bounds);
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
		if (!this.bounds.chunkOverlaps(centerChunk.getPos())) return;
		super.createStructures(registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
		ChunkPos pos = centerChunk.getPos();
		if (pos.equals(fortressChunk(this.bounds, this.layout))) {
			this.startStructure(STRIP_FORTRESS, false, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
		}
		if (pos.equals(bastionChunk(this.bounds, this.layout))) {
			this.startStructure(STRIP_BASTION, true, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
		}
	}

	/**
	 * Starts one of our Nether structures in this chunk (they have no structure sets). With
	 * {@code centre}, the whole structure is shifted so its start piece is centred in the strip:
	 * the jigsaw start sits at the chunk corner and can rotate away from the narrow strip.
	 */
	private void startStructure(
		ResourceKey<Structure> key,
		boolean centre,
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
		StructureStart previous = structureManager.getStartForStructure(holder.value(), chunk);
		RandomState randomState = state.randomState();
		StructureStart start = holder.value().generate(
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
		if (!start.isValid()) {
			AntCraft.LOGGER.warn("AntCraft: {} failed to generate", key.identifier());
			return;
		}
		if (centre) {
			BoundingBox core = start.getPieces().getFirst().getBoundingBox();
			int dx = this.bounds.centerX() - (core.minX() + core.maxX()) / 2;
			int dz = chunk.getPos().getMiddleBlockZ() - (core.minZ() + core.maxZ()) / 2;
			for (StructurePiece piece : start.getPieces()) piece.move(dx, 0, dz);
		}
		structureManager.setStartForStructure(holder.value(), start, chunk);
		AntCraft.LOGGER.info("AntCraft: {} placed, bounds {}", key.identifier(), start.getBoundingBox());
	}

	/** See StripChunkGenerator: keep /locate from generating thousands of empty chunks. */
	@Override
	public com.mojang.datafixers.util.@Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(
		net.minecraft.server.level.ServerLevel level, net.minecraft.core.HolderSet<Structure> wanted, BlockPos pos, int maxSearchRadius, boolean createReference
	) {
		return super.findNearestMapStructure(level, wanted, pos, Math.min(maxSearchRadius, 3), createReference);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		if (this.bounds.chunkOverlaps(region.getCenter())) this.base.spawnOriginalMobs(region);
	}

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
		return this.base.getBaseHeight(x, z, type, heightAccessor, randomState);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		return this.base.getBaseColumn(x, z, heightAccessor, randomState);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		this.base.addDebugScreenInfo(result, randomState, feetPos, samplerContext);
	}
}
