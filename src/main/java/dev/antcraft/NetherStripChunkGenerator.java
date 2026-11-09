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
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.jspecify.annotations.Nullable;

/**
 * The Nether as a strip with the same footprint as the overworld strip (portals map 1:1). Vanilla Nether
 * terrain inside, invisible barrier walls, void outside, a guaranteed fortress and the Nether Key
 * shrine.
 */
public final class NetherStripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator, FrameGenerator {
	/**
	 * Where the fortress, the key shrine and the bastion go. Saved with the world: worlds from
	 * before the bastion have no {@code layout} field and keep layout 1 (fortress 2/3 along the
	 * strip, shrine at 1/6, no bastion); the antcraft:strip preset asks for the latest layout.
	 */
	public static final int LATEST_LAYOUT = NetherLayout.LATEST;

	/**
	 * {@code layout}: 1 and 2 are the original Nether (see above); 3 is the tall Nether
	 * ({@link NetherLayout#LAYOUT_3}), whose {@code base.biome_source} must be {@code antcraft:nether_strip}.
	 */
	public static final MapCodec<NetherStripChunkGenerator> CODEC = RecordCodecBuilder.<NetherStripChunkGenerator>mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.serializedBase),
				StripBounds.SIZE_CODEC.forGetter(g -> g.bounds),
				Codec.intRange(1, LATEST_LAYOUT).optionalFieldOf("layout", 1).forGetter(g -> g.layout)
			)
			.apply(i, NetherStripChunkGenerator::new)
	).validate(NetherStripChunkGenerator::checkLayout);

	private static com.mojang.serialization.DataResult<NetherStripChunkGenerator> checkLayout(NetherStripChunkGenerator g) {
		boolean strip = g.serializedBase.getBiomeSource() instanceof NetherStripBiomeSource;
		if (g.netherLayout.tall() != strip) {
			return com.mojang.serialization.DataResult.error(() -> "AntCraft Nether layout " + g.layout + (strip ? " cannot use" : " needs") + " the antcraft:nether_strip biome source");
		}
		if (g.serializedBase.getBiomeSource() instanceof NetherStripBiomeSource source && source.layout() != g.layout) {
			return com.mojang.serialization.DataResult.error(() -> "AntCraft Nether generator has layout " + g.layout + " but its biome source has layout " + source.layout());
		}
		return com.mojang.serialization.DataResult.success(g);
	}

	public static final ResourceKey<Structure> STRIP_FORTRESS = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "strip_fortress")
	);
	public static final ResourceKey<Structure> STRIP_BASTION = ResourceKey.create(
		Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "strip_bastion")
	);
	/** The generator as saved in the world. */
	private final NoiseBasedChunkGenerator serializedBase;
	/** The vanilla generator that makes the terrain (tall layout: a copy with the raw vanilla biome source, run in a frame). */
	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;
	private final int layout;
	private final NetherLayout netherLayout;
	/** Natural monsters per deep layer (index = layer), from the layer table; null = the biome's. */
	private final WeightedList<MobSpawnSettings.SpawnerData>[] layerMobs;

	public NetherStripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds, int layout) {
		super(base.getBiomeSource());
		this.serializedBase = base;
		this.bounds = bounds;
		this.layout = layout;
		this.netherLayout = NetherLayout.byId(layout);
		if (this.netherLayout.tall() && base.getBiomeSource() instanceof NetherStripBiomeSource source) {
			this.base = new NoiseBasedChunkGenerator(source.base(), base.generatorSettings());
		} else {
			this.base = base;
		}
		@SuppressWarnings("unchecked") WeightedList<MobSpawnSettings.SpawnerData>[] mobs = new WeightedList[this.netherLayout.deepLayers + 1];
		this.layerMobs = mobs;
	}

	public int layout() {
		return this.layout;
	}

	public NetherLayout netherLayout() {
		return this.netherLayout;
	}

	public DeepNetherTerrain terrain(long seed) {
		return DeepNetherTerrain.forSeed(seed, this.bounds, this.netherLayout);
	}

	@Override
	public NoiseBasedChunkGenerator base() {
		return this.base;
	}

	@Override
	public StripBounds bounds() {
		return this.bounds;
	}

	/** Layouts 1 and 2: the vanilla Nether is not moved. Layout 3: moved up by 1904. */
	@Override
	public int frameOffset() {
		return this.netherLayout.frameOffset;
	}

	@Override
	public int frameMinY() {
		return this.base.getMinY() + this.frameOffset();
	}

	@Override
	public int frameHeight() {
		return this.base.getGenDepth();
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
		if (this.netherLayout.tall()) return super.createBiomes(randomState, blender, structureManager, chunk); // the NetherStripBiomeSource
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
		if (this.netherLayout.tall()) {
			DeepNetherTerrain terrain = this.terrain(randomState.seed());
			if (!this.bounds.chunkOverlaps(pos)) {
				return CompletableFuture.completedFuture(chunk).thenApply(c -> {
					shapeChunk(c, this.bounds);
					return c;
				});
			}
			// the vanilla Nether runs in its own coordinates (0..127) on a frame over the real sections 1904..2031
			FrameTerrain.FrameProtoChunk frame = FrameTerrain.frame(chunk, this.frameOffset(), FrameTerrain.NETHER_FRAME, carverBiomeRegion != null ? carverBiomeRegion.registryAccess() : null);
			BiomeManager shifted = new FrameTerrain.ShiftedBiomeManager(biomeManager, this.frameOffset());
			return this.base.buildTerrain(frame, Blender.empty(), randomState, structureManager, shifted, carverBiomeRegion, possibleBiomes).thenApply(f -> {
				FrameTerrain.transfer(frame, chunk);
				this.shapeTall(chunk, terrain);
				return chunk;
			});
		}
		CompletableFuture<ChunkAccess> vanilla = this.bounds.chunkOverlaps(pos)
			? this.base.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
			: CompletableFuture.completedFuture(chunk);
		return vanilla.thenApply(c -> {
			shapeChunk(c, this.bounds);
			return c;
		});
	}

	/** Tall layout, after the vanilla frame: NF0 and its tunnel, the deep layers, the ways down, then the walls. */
	private void shapeTall(ChunkAccess chunk, DeepNetherTerrain terrain) {
		ChunkPos pos = chunk.getPos();
		LevelChunkSection[] sections = chunk.getSections();
		for (LevelChunkSection section : sections) section.acquire();
		try {
			for (int lx = 0; lx < 16; lx++) {
				for (int lz = 0; lz < 16; lz++) {
					int x = pos.getMinBlockX() + lx, z = pos.getMinBlockZ() + lz;
					if (!this.bounds.inside(x, z)) continue;
					terrain.reshapeBand(chunk, lx, lz, x, z);
					terrain.fillColumn(chunk, lx, lz, x, z);
				}
			}
			terrain.placeOres(chunk);
			terrain.cutPaths(chunk);
		} finally {
			for (LevelChunkSection section : sections) section.release();
		}
		shapeChunk(chunk, this.bounds);
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
		BlockState[][] tunnel = this.netherLayout.tall() ? this.snapshotTunnel(level, pos) : null;
		super.applyBiomeDecoration(level, chunk, structureManager);
		if (tunnel != null) this.restoreTunnel(level, pos, tunnel); // band features (basalt pillars, springs) stay out of NF0
		KeyShrine.rebuild(level, pos, this.bounds, this.layout); // after this chunk's fortress pieces and features
		if (this.netherLayout.tall()) {
			DeepNetherTerrain terrain = this.terrain(level.getSeed());
			terrain.decorate(level, this, chunk);
			terrain.repairPaths(level, pos); // after the deep fortress, bastion, portals and fossils
		}
		clipRegion(level, pos, this.bounds);
	}

	/**
	 * Tall layout: NF0 and its closed tunnel (floor base .. tunnel ceiling) of the strip columns in
	 * the 3x3 decoration region, as generated. The vanilla band features run with their usual
	 * height ranges and treat the tunnel floor as one more floor: basalt pillars stood in it and
	 * their hang-off basalt replaced NF0's top bedrock row. Index: column (x, z) in the region.
	 */
	private BlockState[][] snapshotTunnel(WorldGenLevel level, ChunkPos center) {
		int y0 = this.netherLayout.floorBase(0), y1 = this.netherLayout.hallBottom(0) + this.netherLayout.hallHeight + 1;
		BlockState[][] columns = new BlockState[48 * 48][];
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int bx = (center.x() - 1) << 4, bz = (center.z() - 1) << 4;
		for (int dx = 0; dx < 48; dx++) {
			for (int dz = 0; dz < 48; dz++) {
				int x = bx + dx, z = bz + dz;
				if (!this.bounds.inside(x, z)) continue;
				ChunkAccess c = level.getChunk(x >> 4, z >> 4);
				BlockState[] column = new BlockState[y1 - y0 + 1];
				for (int y = y0; y <= y1; y++) column[y - y0] = c.getBlockState(at.set(x, y, z));
				columns[dx * 48 + dz] = column;
			}
		}
		return columns;
	}

	private void restoreTunnel(WorldGenLevel level, ChunkPos center, BlockState[][] columns) {
		int y0 = this.netherLayout.floorBase(0);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int bx = (center.x() - 1) << 4, bz = (center.z() - 1) << 4;
		for (int dx = 0; dx < 48; dx++) {
			for (int dz = 0; dz < 48; dz++) {
				BlockState[] column = columns[dx * 48 + dz];
				if (column == null) continue;
				int x = bx + dx, z = bz + dz;
				for (int i = 0; i < column.length; i++) {
					if (level.getBlockState(at.set(x, y0 + i, z)) != column[i]) level.setBlock(at, column[i], net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
				}
			}
		}
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
		if (!this.netherLayout.tall()) return;
		// the deep fortress (N9, heights 75..97) and the deep bastion (N12, start -559): the band's structures with another anchor shift
		if (pos.equals(deepFortressChunk(this.bounds))) {
			WorldGenShift.withOverride(DEEP_FORTRESS_SHIFT, () -> {
				this.startStructure(STRIP_FORTRESS, false, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
				return null;
			});
		}
		if (pos.equals(deepBastionChunk(this.bounds))) {
			WorldGenShift.withOverride(DEEP_BASTION_SHIFT, () -> {
				this.startStructure(STRIP_BASTION, true, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
				return null;
			});
		}
		for (DeepNetherStarts.Planned planned : DeepNetherStarts.plan(state.getLevelSeed(), this.bounds, this.netherLayout, this.terrain(state.getLevelSeed()))) {
			if (pos.equals(planned.chunk())) DeepNetherStarts.start(planned, registryAccess, structureManager, centerChunk, structureTemplateManager);
		}
	}

	/** Anchor shifts of the deep fortress (48..70 -> 75..97) and the deep bastion (33 -> -559). */
	public static final int DEEP_FORTRESS_SHIFT = 27, DEEP_BASTION_SHIFT = -592;

	/** The deep fortress's start chunk (layout 3): its own chunk row, half way along the strip. */
	public static ChunkPos deepFortressChunk(StripBounds bounds) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.scaledZ(150) >> 4);
	}

	public static ChunkPos deepBastionChunk(StripBounds bounds) {
		return new ChunkPos(bounds.centerX() >> 4, bounds.scaledZ(200) >> 4);
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

	// The frame (tall layout): the vanilla Nether is reported D_N blocks higher. Lithium overwrites
	// NoiseBasedChunkGenerator.getSeaLevel; it is only called here.

	@Override
	public int getSeaLevel() {
		return this.base.getSeaLevel() + this.frameOffset();
	}

	@Override
	public int getMinY() {
		return this.frameMinY();
	}

	@Override
	public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
		return super.getSpawnHeight(heightAccessor) + this.frameOffset();
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		int d = this.frameOffset();
		if (d == 0) return this.base.getBaseHeight(x, z, type, heightAccessor, randomState);
		return this.base.getBaseHeight(x, z, type, LevelHeightAccessor.create(heightAccessor.getMinY() - d, heightAccessor.getHeight()), randomState) + d;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		int d = this.frameOffset();
		if (d == 0) return this.base.getBaseColumn(x, z, heightAccessor, randomState);
		LevelHeightAccessor shifted = LevelHeightAccessor.create(heightAccessor.getMinY() - d, heightAccessor.getHeight());
		NoiseColumn column = this.base.getBaseColumn(x, z, shifted, randomState);
		int minY = Math.max(this.base.getMinY(), shifted.getMinY());
		int height = Math.min(this.base.getMinY() + this.base.getGenDepth(), shifted.getMaxY() + 1) - minY;
		BlockState[] states = new BlockState[Math.max(0, height)];
		for (int i = 0; i < states.length; i++) states[i] = column == null ? DeepTerrain.AIR : column.getBlock(minY + i);
		return new NoiseColumn(minY + d, states);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		this.base.addDebugScreenInfo(result, randomState, feetPos.below(this.frameOffset()), samplerContext);
		if (this.netherLayout.tall()) {
			int layer = this.netherLayout.layerAt(feetPos.getY());
			result.add("AntCraft Nether depth: " + (layer < 0 ? "bedrock" : layer) + (layer >= 1 ? " (" + this.netherLayout.row(layer).englishName() + ")" : ""));
		}
	}

	/**
	 * Tall layout: the deep layers' own natural monsters (Warped Sanctuary none, Ghast Expanse
	 * ghasts, ...) instead of their biome's. Structure spawn overrides (the deep fortress) still win.
	 */
	@Override
	public WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(Level level, StructureManager structureManager, MobCategory category, BlockPos pos) {
		if (category == MobCategory.MONSTER && this.netherLayout.tall() && pos.getY() < this.netherLayout.vanillaMinY()) {
			int layer = this.netherLayout.layerAt(pos.getY());
			if (layer >= 1 && this.netherLayout.row(layer).mobs() != null && !inSpawnOverride(structureManager, category, pos)) {
				WeightedList<MobSpawnSettings.SpawnerData> list = this.layerMobs[layer];
				if (list == null) {
					WeightedList.Builder<MobSpawnSettings.SpawnerData> builder = WeightedList.builder();
					for (NetherRow.Spawn spawn : this.netherLayout.row(layer).mobs()) {
						builder.add(new MobSpawnSettings.SpawnerData(spawn.type(), net.minecraft.util.valueproviders.UniformInt.of(spawn.min(), spawn.max())), spawn.weight());
					}
					this.layerMobs[layer] = list = builder.build();
				}
				return list;
			}
		}
		return super.getMobsAt(level, structureManager, category, pos);
	}

	/**
	 * Whether a structure's spawn override for {@code category} covers {@code pos}, with the same
	 * piece / box test as {@link ChunkGenerator#getMobsAt}. {@code getAllStructuresAt} alone is not
	 * enough: it returns every reference of the chunk at any y, and the band and deep fortress and
	 * bastion boxes reference every strip chunk.
	 */
	private static boolean inSpawnOverride(StructureManager structureManager, MobCategory category, BlockPos pos) {
		for (java.util.Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> entry : structureManager.getAllStructuresAt(pos).entrySet()) {
			net.minecraft.world.level.levelgen.structure.StructureSpawnOverride override = entry.getKey().spawnOverrides().get(category);
			if (override == null) continue;
			boolean piece = override.boundingBox() == net.minecraft.world.level.levelgen.structure.StructureSpawnOverride.BoundingBoxType.PIECE;
			boolean[] inside = {false};
			structureManager.fillStartsForStructure(entry.getKey(), entry.getValue(), start -> {
				if (!inside[0] && (piece ? structureManager.structureHasPieceAt(pos, start) : start.getBoundingBox().isInside(pos))) inside[0] = true;
			});
			if (inside[0]) return true;
		}
		return false;
	}
}
