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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
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
 *
 * <p>{@code layout} 2 is the tall End ({@link EndLayout#LAYOUT_2}, dimension type
 * {@code antcraft:strip_end_tall}, biome source {@code antcraft:end_strip}): the vanilla End stays
 * at y 0..255 and the tiers of {@link EndTierTerrain} are added above and below it in the tier
 * zone. Layout 1 (no {@code layout} field) is the End as before, bit for bit.
 */
public final class EndStripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator, FrameGenerator {
	public static final int MIN_Z = -1400, MAX_Z = 149;
	/** Where players arrive from the overworld (vanilla: 100, 50, 0). The obsidian platform is below it. */
	public static final int SPAWN_Y = 50, SPAWN_Z = 100;
	/** The gateway exit search aims here (vanilla: 1024 blocks out from the gateway). */
	public static final int GATEWAY_EXIT_Z = -1100;
	/** The two vanilla gateway slots on the z axis (0, ±96); the other 18 are left out. */
	public static final int GATEWAY_NORTH = 15, GATEWAY_SOUTH = 5;

	public static final MapCodec<EndStripChunkGenerator> CODEC = RecordCodecBuilder.<EndStripChunkGenerator>mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.serializedBase),
				Codec.intRange(StripBounds.MIN_WIDTH, StripBounds.MAX_WIDTH).optionalFieldOf("width").forGetter(g -> Optional.of(g.bounds.width())),
				Codec.BOOL.optionalFieldOf("size_from_config", false).forGetter(g -> false),
				Codec.intRange(1, EndLayout.LATEST).optionalFieldOf("layout", 1).forGetter(g -> g.endLayout.id)
			)
			.apply(i, (base, width, fromConfig, layout) -> new EndStripChunkGenerator(base, bounds(width.orElseGet(
				() -> fromConfig ? AntCraftConfig.get().stripWidth : StripBounds.DEFAULT_WIDTH
			)), layout))
	).validate(EndStripChunkGenerator::checkLayout);

	private static com.mojang.serialization.DataResult<EndStripChunkGenerator> checkLayout(EndStripChunkGenerator g) {
		boolean strip = g.serializedBase.getBiomeSource() instanceof EndStripBiomeSource;
		if (g.endLayout.tall != strip) {
			return com.mojang.serialization.DataResult.error(() -> "AntCraft End layout " + g.endLayout.id + (strip ? " cannot use" : " needs") + " the antcraft:end_strip biome source");
		}
		if (g.serializedBase.getBiomeSource() instanceof EndStripBiomeSource source && source.layout() != g.endLayout.id) {
			return com.mojang.serialization.DataResult.error(() -> "AntCraft End generator has layout " + g.endLayout.id + " but its biome source has layout " + source.layout());
		}
		return com.mojang.serialization.DataResult.success(g);
	}

	/** The generator as saved in the world. */
	private final NoiseBasedChunkGenerator serializedBase;
	/** The vanilla generator that makes the terrain (tall layout: a copy with the raw vanilla End biome source). */
	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;
	private final EndLayout endLayout;
	/** The Silent Drift's (U3) natural monsters. */
	private @Nullable WeightedList<MobSpawnSettings.SpawnerData> driftMobs;

	public EndStripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds) {
		this(base, bounds, 1);
	}

	public EndStripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds, int layout) {
		super(base.getBiomeSource());
		this.serializedBase = base;
		this.bounds = bounds;
		this.endLayout = EndLayout.byId(layout);
		if (this.endLayout.tall && base.getBiomeSource() instanceof EndStripBiomeSource source) {
			this.base = new NoiseBasedChunkGenerator(source.base(), base.generatorSettings());
		} else {
			this.base = base;
		}
	}

	public EndLayout endLayout() {
		return this.endLayout;
	}

	public EndTierTerrain tiers(long seed) {
		return EndTierTerrain.forSeed(seed, this.bounds, this.endLayout);
	}

	/** The tall End's generator of a level (End layout 2), else null. */
	public static @Nullable EndStripChunkGenerator tallEnd(@Nullable Level level) {
		return level instanceof ServerLevel server && server.getChunkSource().getGenerator() instanceof EndStripChunkGenerator end && end.endLayout.tall ? end : null;
	}

	public static boolean isTallEnd(@Nullable ChunkGenerator generator) {
		return generator instanceof EndStripChunkGenerator end && end.endLayout.tall;
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

	/** The vanilla End is never moved (D_E = 0, also in the tall End): no frame offset. */
	@Override
	public int frameOffset() {
		return 0;
	}

	@Override
	public int frameMinY() {
		return this.base.getMinY() + this.frameOffset();
	}

	@Override
	public int frameHeight() {
		return this.base.getGenDepth();
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		if (this.endLayout.tall) {
			// The tall End also fills the chunks next to the strip: vanilla's end_spike checks the
			// biome at its chunk's decoration origin, and the fuzzy biome lookup there can read the
			// neighbour chunk's biomes (plains when left empty), which silently drops that pillar.
			if (!this.bounds.chunkTouches(pos) && !this.bounds.chunkTouches(new ChunkPos(pos.x() + 1, pos.z())) && !this.bounds.chunkTouches(new ChunkPos(pos.x() - 1, pos.z()))
				&& !this.bounds.chunkTouches(new ChunkPos(pos.x(), pos.z() + 1)) && !this.bounds.chunkTouches(new ChunkPos(pos.x(), pos.z() - 1))) {
				return CompletableFuture.completedFuture(chunk);
			}
			return super.createBiomes(randomState, blender, structureManager, chunk); // the EndStripBiomeSource
		}
		if (!this.bounds.chunkTouches(pos)) return CompletableFuture.completedFuture(chunk);
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
		// the vanilla End noise only covers y 0..127, also in the tall dimension (NBCG clamps to its noise settings)
		CompletableFuture<ChunkAccess> vanilla = this.bounds.chunkOverlaps(pos)
			? this.base.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
			: CompletableFuture.completedFuture(chunk);
		if (!this.endLayout.tall) {
			return vanilla.thenApply(c -> {
				NetherStripChunkGenerator.shapeChunk(c, this.bounds);
				return c;
			});
		}
		EndTierTerrain tiers = this.tiers(randomState.seed());
		return vanilla.thenApply(c -> {
			if (this.bounds.chunkOverlaps(pos)) tiers.fill(c);
			NetherStripChunkGenerator.shapeChunk(c, this.bounds);
			return c;
		});
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		ChunkPos pos = chunk.getPos();
		if (!this.bounds.chunkOverlaps(pos)) return;
		super.applyBiomeDecoration(level, chunk, structureManager);
		if (this.endLayout.tall) this.tiers(level.getSeed()).decorate(level, chunk);
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
		if (!this.endLayout.tall) return;
		for (EndLayout.Tier tier : this.endLayout.tiers) {
			if (tier.city() == 0) continue;
			BlockPos site = citySite(this.bounds, this.endLayout, tier);
			if (ChunkPos.containing(site).equals(centerChunk.getPos())) {
				this.startTierCity(tier, site, registryAccess, state.getLevelSeed(), structureManager, centerChunk, structureTemplateManager);
			}
		}
	}

	/** Where a tier's end city starts: on its flat pad, 1 above the pad's top. */
	public static BlockPos citySite(StripBounds bounds, EndLayout layout, EndLayout.Tier tier) {
		return new BlockPos(bounds.centerX(), tier.y() + 3, layout.cityZ(tier));
	}

	public static ResourceKey<Structure> cityKey(EndLayout.Tier tier) {
		return ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "end_tier_city_" + tier.id()));
	}

	/**
	 * A tier's end city (E1 small, E2 with a ship, E6 the Grand Spire), started by hand on the
	 * tier's city pad: the vanilla end-city piece generator, best of 16 seeded tries. E1 keeps the
	 * smallest city of at least 4 pieces, E6 the biggest, E2 the first whose ship (with its elytra)
	 * lies inside the strip (else the biggest with a ship).
	 */
	private void startTierCity(
		EndLayout.Tier tier, BlockPos site, RegistryAccess registryAccess, long seed, StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templates
	) {
		Holder<Structure> holder = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(cityKey(tier)).orElse(null);
		if (holder == null) {
			AntCraft.LOGGER.error("AntCraft: structure {} is missing", cityKey(tier).identifier());
			return;
		}
		List<StructurePiece> best = null;
		int bestScore = Integer.MIN_VALUE;
		for (int i = 0; i < 16; i++) {
			RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 17300 + tier.step() * 100L + i));
			Rotation rotation = Rotation.getRandom(random);
			List<StructurePiece> pieces = new ArrayList<>();
			EndCityPieces.startHouseTower(templates, site, rotation, pieces, random);
			int score;
			if (tier.city() == 1) {
				score = pieces.size() >= 4 ? -pieces.size() : Integer.MIN_VALUE + 1;
			} else if (tier.city() == 2) {
				StructurePiece ship = ship(pieces);
				if (ship == null) {
					score = Integer.MIN_VALUE + 1;
				} else {
					int shipX = ship.getBoundingBox().getCenter().getX();
					boolean inside = shipX >= this.bounds.minX() + 2 && shipX <= this.bounds.maxX() - 2;
					score = (inside ? 1_000_000 : 0) + pieces.size();
					if (inside) {
						best = pieces;
						break;
					}
				}
			} else {
				score = pieces.size();
			}
			if (score > bestScore) {
				bestScore = score;
				best = pieces;
			}
		}
		if (best == null || best.isEmpty()) return;
		StructureStart previous = structureManager.getStartForStructure(holder.value(), chunk);
		StructureStart start = new StructureStart(holder.value(), chunk.getPos(), previous != null ? previous.getReferences() : 0, new PiecesContainer(best));
		structureManager.setStartForStructure(holder.value(), start, chunk);
		AntCraft.LOGGER.info("AntCraft: {} placed, {} pieces{}, bounds {}", cityKey(tier).identifier(), best.size(), ship(best) != null ? " with a ship" : "", start.getBoundingBox());
	}

	private static @Nullable StructurePiece ship(List<StructurePiece> pieces) {
		for (StructurePiece piece : pieces) {
			if (piece instanceof TemplateStructurePiece template && "ship".equals(((dev.antcraft.mixin.TemplateStructurePieceAccessor) template).antcraft$templateName())) return piece;
		}
		return null;
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

	/** The tall End's Silent Drift (U3) spawns phantoms with its endermen; everything else is the biome's. */
	@Override
	public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(Level level, StructureManager structureManager, MobCategory category, BlockPos pos) {
		if (category == MobCategory.MONSTER && this.endLayout.tall && EndLayout.inZone(pos.getZ())) {
			EndLayout.Tier tier = this.endLayout.tierAt(pos.getY());
			if (tier != null && tier.darkTop()) {
				WeightedList<MobSpawnSettings.SpawnerData> list = this.driftMobs;
				if (list == null) {
					this.driftMobs = list = WeightedList.<MobSpawnSettings.SpawnerData>builder()
						.add(new MobSpawnSettings.SpawnerData(EntityTypes.PHANTOM, net.minecraft.util.valueproviders.UniformInt.of(1, 2)), 10)
						.add(new MobSpawnSettings.SpawnerData(EntityTypes.ENDERMAN, net.minecraft.util.valueproviders.UniformInt.of(1, 2)), 6)
						.build();
				}
				return list;
			}
		}
		return super.getMobsAt(level, structureManager, category, pos);
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

	/** The vanilla band of the tall End, as a height accessor (heights outside it are "not found", as in a 0..255 End). */
	private static final LevelHeightAccessor BASE_BAND = LevelHeightAccessor.create(EndLayout.BASE_MIN_Y, EndLayout.BASE_MAX_Y - EndLayout.BASE_MIN_Y + 1);

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		if (this.endLayout.tall) return this.base.getBaseHeight(x, z, type, BASE_BAND, randomState);
		return this.base.getBaseHeight(x, z, type, heightAccessor, randomState);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		if (this.endLayout.tall) return this.base.getBaseColumn(x, z, BASE_BAND, randomState);
		return this.base.getBaseColumn(x, z, heightAccessor, randomState);
	}

	/**
	 * The tall End: heights for feature placement as in a 0..255 End. Vanilla End features place
	 * on the heightmap (chorus, gateway returns); with tiers above, the heightmap would put them on
	 * a tier, and an empty column would report the dimension's bottom. This returns the top of the
	 * vanilla band instead: the heightmap value if it lies in the band, else a scan of the band, and
	 * the band's bottom for a column with nothing in it (the 0..255 End's "nothing here").
	 */
	public static int baseBandHeight(WorldGenLevel level, Heightmap.Types type, int x, int z, int height) {
		if (height > EndLayout.BASE_MIN_Y && height <= EndLayout.BASE_MAX_Y + 1) return height;
		if (height <= EndLayout.BASE_MIN_Y) return EndLayout.BASE_MIN_Y;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int y = EndLayout.BASE_MAX_Y; y >= EndLayout.BASE_MIN_Y; y--) {
			BlockState state = level.getBlockState(at.set(x, y, z));
			if (type.isOpaque().test(state)) return y + 1;
		}
		return EndLayout.BASE_MIN_Y;
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		this.base.addDebugScreenInfo(result, randomState, feetPos, samplerContext);
		if (this.endLayout.tall && EndLayout.inZone(feetPos.getZ())) {
			EndLayout.Tier tier = this.endLayout.tierAt(feetPos.getY());
			if (tier != null) result.add("AntCraft End tier: " + tier.id() + " (" + tier.englishName() + ")");
		}
	}
}
