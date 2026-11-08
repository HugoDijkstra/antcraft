package dev.antcraft;

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
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * The Nether as a strip with the same footprint as the overworld strip (portals map 1:1). Vanilla Nether
 * terrain inside, invisible barrier walls, void outside, a guaranteed fortress and the Nether Key
 * shrine.
 */
public final class NetherStripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator {
	public static final MapCodec<NetherStripChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.base),
				StripBounds.SIZE_CODEC.forGetter(g -> g.bounds)
			)
			.apply(i, NetherStripChunkGenerator::new)
	);

	public static final ResourceKey<Structure> STRIP_FORTRESS = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "strip_fortress")
	);
	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;

	public NetherStripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds) {
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

	/** The guaranteed fortress starts here: in the middle of the strip's width, 2/3 along it (chunk 0, 12 for 20x300). */
	public static ChunkPos fortressChunk(StripBounds bounds) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.scaledZ(200) >> 4);
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
			this.shapeChunk(c);
			return c;
		});
	}

	private void shapeChunk(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		for (LevelChunkSection section : sections) section.acquire();
		try {
			int minY = chunk.getMinY(), maxY = chunk.getMaxY();
			for (int lx = 0; lx < 16; lx++) {
				for (int lz = 0; lz < 16; lz++) {
					int x = pos.getMinBlockX() + lx, z = pos.getMinBlockZ() + lz;
					if (this.bounds.inside(x, z)) continue;
					boolean wall = this.bounds.isWall(x, z);
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
						if (this.bounds.inside(x, z)) continue;
						if (this.bounds.isWall(x, z)) {
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
	private void clipRegion(WorldGenLevel level, ChunkPos center) {
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
		KeyShrine.rebuild(level, pos, this.bounds); // after this chunk's fortress pieces and features
		this.clipRegion(level, pos);
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
		if (!centerChunk.getPos().equals(fortressChunk(this.bounds))) return;

		Holder<Structure> holder = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(STRIP_FORTRESS).orElse(null);
		if (holder == null) {
			AntCraft.LOGGER.error("AntCraft: structure {} is missing", STRIP_FORTRESS.identifier());
			return;
		}
		StructureStart previous = structureManager.getStartForStructure(holder.value(), centerChunk);
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
			centerChunk.getPos(),
			previous != null ? previous.getReferences() : 0,
			centerChunk,
			biome -> true
		);
		if (start.isValid()) {
			structureManager.setStartForStructure(holder.value(), start, centerChunk);
			AntCraft.LOGGER.info("AntCraft: nether fortress placed, bounds {}", start.getBoundingBox());
		} else {
			AntCraft.LOGGER.warn("AntCraft: nether fortress failed to generate");
		}
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
