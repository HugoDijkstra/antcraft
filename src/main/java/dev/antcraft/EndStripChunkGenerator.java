package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * The End as a strip: the world's strip width, centred on x = 0, from z = -1400 to 149. That holds
 * the main island (podium at 0, 0; arrival platform at 0, 50, 100) and the outer islands along -z
 * (they begin about 1024 blocks out). Vanilla End terrain inside, barrier walls, void outside.
 *
 * <p>The fight is fitted into the strip by mixins: the pillars ({@link #strippedSpikes}), the
 * dragon's flight path, the respawn rebuild, the gateways and the gateway exit search.
 */
public final class EndStripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator {
	public static final int MIN_Z = -1400, MAX_Z = 149;
	/** Where players arrive from the overworld (vanilla: 100, 50, 0). The obsidian platform is below it. */
	public static final int SPAWN_Y = 50, SPAWN_Z = 100;
	/** The gateway exit search aims here (vanilla: 1024 blocks out from the gateway). */
	public static final int GATEWAY_EXIT_Z = -1100;
	/** The two vanilla gateway slots on the z axis (0, ±96); the other 18 are left out. */
	public static final int GATEWAY_NORTH = 15, GATEWAY_SOUTH = 5;

	public static final MapCodec<EndStripChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.base),
				Codec.intRange(StripBounds.MIN_WIDTH, StripBounds.MAX_WIDTH).optionalFieldOf("width").forGetter(g -> Optional.of(g.bounds.width())),
				Codec.BOOL.optionalFieldOf("size_from_config", false).forGetter(g -> false)
			)
			.apply(i, (base, width, fromConfig) -> new EndStripChunkGenerator(base, bounds(width.orElseGet(
				() -> fromConfig ? AntCraftConfig.get().stripWidth : StripBounds.DEFAULT_WIDTH
			))))
	);

	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;

	public EndStripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds) {
		super(base.getBiomeSource());
		this.base = base;
		this.bounds = bounds;
	}

	/** x from -width/2 (so 20 wide is -10..9), z -1400..149. */
	public static StripBounds bounds(int width) {
		int w = Math.clamp(width, StripBounds.MIN_WIDTH, StripBounds.MAX_WIDTH);
		int minX = -(w / 2);
		return new StripBounds(minX, minX + w - 1, MIN_Z, MAX_Z);
	}

	public static @Nullable StripBounds endBounds(Level level) {
		return level instanceof ServerLevel server && server.getChunkSource().getGenerator() instanceof EndStripChunkGenerator end ? end.bounds : null;
	}

	public static BlockPos spawnPoint(StripBounds bounds) {
		return new BlockPos(bounds.centerX(), SPAWN_Y, SPAWN_Z);
	}

	/**
	 * The ten pillars, moved from the 42-block ring into the strip: five on each side of the podium
	 * along z (24, 36, ... 72 blocks out), alternating x so neighbours stay apart. Size, height and
	 * cages are vanilla's, so crystal count, the fight and the respawn work as usual.
	 */
	public static List<EndSpikeFeature.EndSpike> strippedSpikes(List<EndSpikeFeature.EndSpike> vanilla, StripBounds bounds) {
		List<EndSpikeFeature.EndSpike> result = new ArrayList<>(vanilla.size());
		for (int i = 0; i < vanilla.size(); i++) {
			EndSpikeFeature.EndSpike spike = vanilla.get(i);
			int side = i < 5 ? -1 : 1;
			int k = i % 5;
			int z = side * (24 + 12 * k);
			int r = spike.getRadius();
			int east = Math.max(0, Math.min(4, bounds.maxX() - r - bounds.centerX()));
			int west = Math.max(0, Math.min(4, bounds.centerX() - r - bounds.minX()));
			int x = ((k % 2 == 0) == (side > 0)) ? bounds.centerX() + east : bounds.centerX() - west;
			result.add(new EndSpikeFeature.EndSpike(x, z, r, spike.getHeight(), spike.isGuarded()));
		}
		return result;
	}

	@Override
	public NoiseBasedChunkGenerator base() {
		return this.base;
	}

	@Override
	public StripBounds bounds() {
		return this.bounds;
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
			NetherStripChunkGenerator.shapeChunk(c, this.bounds);
			return c;
		});
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		ChunkPos pos = chunk.getPos();
		if (!this.bounds.chunkOverlaps(pos)) return;
		super.applyBiomeDecoration(level, chunk, structureManager);
		NetherStripChunkGenerator.clipRegion(level, pos, this.bounds);
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
	}

	/** See StripChunkGenerator: keep /locate from generating thousands of empty chunks. */
	@Override
	public com.mojang.datafixers.util.@Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(
		ServerLevel level, net.minecraft.core.HolderSet<Structure> wanted, BlockPos pos, int maxSearchRadius, boolean createReference
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
