package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
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
public final class StripChunkGenerator extends ChunkGenerator implements WrappedNoiseGenerator, StripGenerator, FrameGenerator {
	/**
	 * {@code layout}: the {@link StripLayout} id, saved with the world. Worlds from before layouts
	 * existed have no field and get layout 1; the default is not written back, so their generator
	 * JSON stays the same.
	 */
	public static final MapCodec<StripChunkGenerator> CODEC = RecordCodecBuilder.<StripChunkGenerator>mapCodec(
		i -> i.group(
				NoiseBasedChunkGenerator.CODEC.codec().fieldOf("base").forGetter(g -> g.serializedBase),
				StripBounds.SIZE_CODEC.forGetter(g -> g.bounds),
				Codec.intRange(1, StripLayout.LATEST).optionalFieldOf("layout", 1).forGetter(g -> g.layout.id),
				Codec.intRange(1, StripChunkGenerator.LATEST_VAULT_SELECTION).optionalFieldOf("vault", 1).forGetter(g -> g.vaultSelection)
			)
			.apply(i, StripChunkGenerator::new)
	).validate(StripChunkGenerator::checkLayout);

	/**
	 * The generator and its {@link StripBiomeSource} must agree on the layout. A mismatch is a
	 * broken world_gen_settings entry: report it as a codec error (the world refuses to load with
	 * a message) instead of throwing out of the codec.
	 */
	private static DataResult<StripChunkGenerator> checkLayout(StripChunkGenerator g) {
		if (g.serializedBase.getBiomeSource() instanceof StripBiomeSource source && source.layout() != g.layout.id) {
			return DataResult.error(() -> "AntCraft strip generator has layout " + g.layout.id + " but its biome source has layout " + source.layout());
		}
		return DataResult.success(g);
	}

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

	/** The generator as saved in the world (its biome source is the {@link StripBiomeSource}). */
	private final NoiseBasedChunkGenerator serializedBase;
	/**
	 * The vanilla generator that makes the terrain. Layout 1: {@link #serializedBase} itself. Tall
	 * layouts: a copy holding the raw vanilla biome source (the carvers ask it for biomes in vanilla
	 * coordinates), run in a {@link FrameTerrain} frame.
	 */
	private final NoiseBasedChunkGenerator base;
	private final StripBounds bounds;
	private final StripLayout layout;
	/**
	 * How the Trial Vault is chosen among its tries (saved with the world, so a world keeps its
	 * vault and the starts planned around its exit): 1 = 1.0.20's best-of-16, 2 = best of 32 for
	 * in-strip trial spawners and a short exit well (new tall worlds, through the preset).
	 */
	private final int vaultSelection;
	static final int LATEST_VAULT_SELECTION = 2;
	/** y ranges where decoration can place blocks: the vanilla world, each forest band, the deep city. */
	private final int[][] spillRanges;
	/** Per wall column: lowest y that is barrier (everything below is bedrock). */
	private final Map<Long, Integer> wallSplit = new ConcurrentHashMap<>();

	public StripChunkGenerator(NoiseBasedChunkGenerator base, StripBounds bounds, int layout, int vaultSelection) {
		super(base.getBiomeSource());
		this.serializedBase = base;
		this.bounds = bounds;
		this.layout = StripLayout.byId(layout);
		this.vaultSelection = vaultSelection;
		if (this.layout.frameOffset != 0) {
			if (!(base.getBiomeSource() instanceof StripBiomeSource source)) {
				throw new IllegalArgumentException("AntCraft layout " + layout + " needs the antcraft:strip biome source");
			}
			this.base = new NoiseBasedChunkGenerator(source.base(), base.generatorSettings());
		} else {
			this.base = base;
		}
		this.spillRanges = spillRanges(this.layout);
	}

	public StripLayout layout() {
		return this.layout;
	}

	@Override
	public int frameOffset() {
		return this.layout.frameOffset;
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
	public NoiseBasedChunkGenerator base() {
		return this.base;
	}

	@Override
	public StripBounds bounds() {
		return this.bounds;
	}

	private DeepTerrain terrain(long seed) {
		return DeepTerrain.forSeed(seed, this.bounds, this.layout);
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
			int surface = this.vanillaBaseHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
			return Math.max(surface - 1, this.layout.vanillaMinY);
		});
	}

	/** The vanilla generator's surface height in real coordinates (moved by the frame offset). */
	private int vanillaBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
		int d = this.frameOffset();
		if (d == 0) return this.base.getBaseHeight(x, z, type, heightAccessor, randomState);
		// "nothing found" is the accessor's min y in both coordinate systems
		return this.base.getBaseHeight(x, z, type, LevelHeightAccessor.create(heightAccessor.getMinY() - d, heightAccessor.getHeight()), randomState) + d;
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
		List<BoundingBox> cityPieces = this.findDeepPieces(structureManager, pos);
		VaultPlan vault = this.layout.vaultLayer >= 1 && this.bounds.chunkOverlaps(pos) ? VaultPlan.find(structureManager, pos, this.bounds) : null;
		if (this.framed() && this.bounds.chunkOverlaps(pos)) {
			// the vanilla terrain runs in its own coordinates on a frame over the real sections
			FrameTerrain.FrameProtoChunk frame = FrameTerrain.frame(
				chunk, this.frameOffset(), FrameTerrain.OVERWORLD_FRAME, carverBiomeRegion != null ? carverBiomeRegion.registryAccess() : null
			);
			BiomeManager shifted = new FrameTerrain.ShiftedBiomeManager(biomeManager, this.frameOffset());
			return this.base.buildTerrain(frame, Blender.empty(), randomState, structureManager, shifted, carverBiomeRegion, possibleBiomes).thenApply(f -> {
				FrameTerrain.transfer(frame, chunk);
				this.shapeChunk(chunk, this.terrain(randomState.seed()), cityPieces, vault, randomState);
				return chunk;
			});
		}
		CompletableFuture<ChunkAccess> vanilla = this.bounds.chunkOverlaps(pos)
			? this.base.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes)
			: CompletableFuture.completedFuture(chunk);
		return vanilla.thenApply(c -> {
			this.shapeChunk(c, this.terrain(randomState.seed()), cityPieces, vault, randomState);
			return c;
		});
	}

	/** Chunk upgrade step 1 ({@link WorldUpgrade}): see {@link DeepTerrain#retrofit}. */
	public void retrofitForests(ServerLevel level, LevelChunk chunk) {
		if (!this.bounds.chunkOverlaps(chunk.getPos())) return;
		this.terrain(level.getSeed()).retrofit(level, chunk, this.findDeepPieces(level.structureManager(), chunk.getPos()));
	}

	/**
	 * True for AntCraft's own deep starts: every {@code antcraft:} structure whose path starts with
	 * {@code deep_}. Tall layouts tell deep starts by key, not by height: vanilla igloos and ocean
	 * ruins are started at a placeholder y below the moved vanilla world and re-seated later.
	 */
	public static boolean isDeepKey(RegistryAccess registryAccess, Structure structure) {
		Identifier id = registryAccess.lookupOrThrow(Registries.STRUCTURE).getKey(structure);
		return id != null && id.getNamespace().equals(AntCraft.MOD_ID) && id.getPath().startsWith("deep_");
	}

	/**
	 * Boxes of deep structure pieces (ancient city, deep villages, ...) that come near this chunk,
	 * the ones the terrain flattens or carves around. Tall layouts tag them ({@link DeepBox}) and
	 * leave out the mineshafts and the Trial Vault (they carve their own space).
	 */
	private List<BoundingBox> findDeepPieces(StructureManager structureManager, ChunkPos pos) {
		return this.findDeepPieces(structureManager, pos, false, 4);
	}

	private List<BoundingBox> findDeepPieces(StructureManager structureManager, ChunkPos pos, boolean all, int margin) {
		return this.findDeepPieces(structureManager, pos, all, margin, structureManager.startsForStructure(pos.x(), pos.z(), s -> true));
	}

	/**
	 * Deep pieces near a chunk from the starts referenced by it and its 8 neighbours: a piece just
	 * across the chunk border belongs to a start the chunk itself may not reference.
	 */
	private List<BoundingBox> findDeepPiecesAround(WorldGenLevel level, StructureManager structureManager, ChunkPos pos, int margin) {
		java.util.Set<StructureStart> starts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		starts.addAll(structureManager.startsForStructure(pos.x(), pos.z(), s -> true));
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : structureManager.getAllStructuresAt(new BlockPos(pos.getMinBlockX() + dx * 16, 0, pos.getMinBlockZ() + dz * 16)).entrySet()) {
					for (long key : e.getValue()) {
						ChunkPos at = ChunkPos.unpack(key);
						// the region holds structure starts out to 8 chunks (as for the chunk's own references)
						if (pos.getChessboardDistance(at.x(), at.z()) > 8) continue;
						StructureStart start = structureManager.getStartForStructure(e.getKey(), level.getChunk(at.x(), at.z(), net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS));
						if (start != null && start.isValid()) starts.add(start);
					}
				}
			}
		}
		return this.findDeepPieces(structureManager, pos, true, margin, starts);
	}

	private List<BoundingBox> findDeepPieces(StructureManager structureManager, ChunkPos pos, boolean all, int margin, java.util.Collection<StructureStart> starts) {
		List<BoundingBox> boxes = new ArrayList<>();
		BoundingBox chunkBox = new BoundingBox(pos.getMinBlockX() - margin, this.layout.worldMinY, pos.getMinBlockZ() - margin, pos.getMaxBlockX() + margin, this.layout.worldMaxY, pos.getMaxBlockZ() + margin);
		boolean byKey = this.layout.id >= 2;
		var registry = structureManager.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (StructureStart start : starts) {
			if (byKey ? !isDeepKey(structureManager.registryAccess(), start.getStructure()) : start.getBoundingBox().maxY() >= this.layout.vanillaMinY) continue;
			if (!byKey) {
				for (StructurePiece piece : start.getPieces()) {
					if (piece.getBoundingBox().intersects(chunkBox)) boxes.add(piece.getBoundingBox());
				}
				continue;
			}
			Identifier id = registry.getKey(start.getStructure());
			String path = id == null ? "" : id.getPath();
			boolean ownSpace = path.startsWith("deep_mineshaft") || path.equals("deep_trial_vault");
			if (ownSpace && !all) continue;
			boolean trailRuins = path.equals("deep_trail_ruins"), igloo = path.equals("deep_igloo"), city = path.equals("deep_ancient_city");
			for (StructurePiece piece : start.getPieces()) {
				BoundingBox box = piece.getBoundingBox();
				if (all && path.equals("deep_desert_pyramid")) box = new BoundingBox(box.minX(), box.minY() - DeepStarts.PYRAMID_TNT_DEPTH, box.minZ(), box.maxX(), box.maxY(), box.maxZ());
				if (!box.intersects(chunkBox)) continue;
				int layer = this.layout.layerAt(box.maxY());
				boolean basement = igloo && layer >= 1 && box.maxY() < this.layout.forestFloor(layer) - 2;
				DeepBox.Kind kind = trailRuins || basement ? DeepBox.Kind.BURIED : city ? DeepBox.Kind.CITY : DeepBox.Kind.FOREST;
				boxes.add(new DeepBox(box, kind));
			}
		}
		return boxes;
	}

	private void shapeChunk(ChunkAccess chunk, DeepTerrain terrain, List<BoundingBox> cityPieces, @Nullable VaultPlan vault, RandomState randomState) {
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
						terrain.fillColumn(chunk, lx, lz, x, z, cityPieces, vault);
					} else {
						clearColumnRaw(chunk, lx, lz);
						if (this.bounds.isWall(x, z)) {
							int split = this.wallSplitY(x, z, heightAccessor, randomState);
							for (int y = this.layout.worldMinY; y <= this.layout.worldMaxY; y++) {
								DeepTerrain.set(chunk, lx, y, lz, y < split ? DeepTerrain.BEDROCK : DeepTerrain.BARRIER);
							}
						}
					}
				}
			}
			if (this.bounds.chunkOverlaps(pos)) {
				terrain.placeOres(chunk);
				PortalRoom.build(chunk, terrain.seed(), this.bounds, this.layout);
				terrain.cutHoles(chunk, vault);
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
						heightmap.update(lx, this.layout.worldMaxY, lz, DeepTerrain.BARRIER);
					} else {
						int first = heightmap.getFirstAvailable(lx, lz);
						if (first > chunk.getMinY()) heightmap.update(lx, first - 1, lz, DeepTerrain.AIR);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ clipping after decoration

	private static int[][] spillRanges(StripLayout layout) {
		if (layout.id == 1) {
			int[][] ranges = new int[layout.deepLayers + 2][];
			ranges[0] = new int[] {layout.vanillaMinY, layout.worldMaxY};
			for (int layer = 1; layer <= layout.deepLayers; layer++) {
				int floor = layout.forestFloor(layer);
				ranges[layer] = new int[] {floor - 8, floor + layout.forestHeight + 8};
			}
			ranges[layout.deepLayers + 1] = new int[] {layout.cityMinY(), layout.cityMaxY()};
			return ranges;
		}
		// tall layouts: the vanilla band (and a little below it: vanilla sculk spreads down the outside of the
		// walls past the first floor), every forest band, the city and the Trial Vault
		List<int[]> ranges = new ArrayList<>();
		ranges.add(new int[] {layout.vanillaMinY - 8, layout.worldMaxY});
		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			if (!layout.layer(layer).hasForest()) continue;
			int floor = layout.forestFloor(layer);
			ranges.add(new int[] {floor - 8, floor + layout.forestHeight + 8});
		}
		ranges.add(new int[] {layout.cityMinY(), layout.cityMaxY()});
		if (layout.vaultLayer >= 1) ranges.add(new int[] {layout.layerBottom(layout.vaultLayer), layout.layerTop(layout.vaultLayer)});
		for (int layer = 1; layer <= layout.deepLayers; layer++) {
			LayerSpec spec = layout.layer(layer);
			int floor = layout.forestFloor(layer);
			// the mineshafts' cave bands
			if (spec.mineshaft() != LayerSpec.Mineshaft.NONE) ranges.add(DeepStarts.mineshaftBand(layout, layer));
			// the buried trail ruins reach below the forest band, the igloo basement too
			if (spec.extraStructures().contains("deep_trail_ruins")) ranges.add(new int[] {floor - 30, floor - 9});
			if (spec.extraStructures().contains("deep_igloo")) ranges.add(new int[] {floor - 42, floor - 9});
			// cave features that grow several blocks (geodes, sulfur spikes, sculk, blue ice)
			LayerTheme theme = spec.theme();
			if (theme == LayerTheme.CRYSTAL_HOLLOWS || theme == LayerTheme.SULFUR_SPRINGS || theme == LayerTheme.DEEP_DARK || theme == LayerTheme.FROZEN_DEPTHS) {
				ranges.add(new int[] {layout.layerBottom(layer) + layout.hallHeight, layout.layerTop(layer)});
			}
		}
		return ranges.toArray(int[][]::new);
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
						for (int[] range : this.spillRanges) {
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
		List<BoundingBox> deepPieces = this.findDeepPieces(structureManager, pos);
		VaultPlan vault = this.layout.vaultLayer >= 1 ? VaultPlan.find(structureManager, pos, this.bounds) : null;
		UndergroundForest.placeTrees(level, this, chunk, terrain, deepPieces, vault);
		if (this.layout.id >= 2) {
			// themed features before the ground plants and the light grid, which would take their spots
			ThemeDecor.decorate(level, this, chunk, terrain, deepPieces, vault);
			ThemeDecor.dungeons(level, this, chunk, terrain, this.findDeepPiecesAround(level, structureManager, pos, 16), vault);
			ThemeDecor.cleanShriekers(level, terrain, pos, this.findDeepPiecesAround(level, structureManager, pos, 32), vault);
			terrain.clearArenaSculk(level, pos);
			if (vault != null) vault.cutWells(level, pos, this.layout, this.bounds);
		}
		terrain.decorateForests(level, chunk, deepPieces); // plants and lights after trees, so they don't block them
		PortalRoom.placeBlockEntities(level, chunk, level.getSeed(), this.bounds, this.layout);
		if (pos.equals(this.layout.cityChunk(this.bounds))) this.placeCityKeyChest(level, pos, deepPieces);
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
			if (box.minY() < this.layout.cityMinY() - 10 || box.maxY() > this.layout.cityMaxY()) continue; // a village, not the city
			for (int x = Math.max(minX, box.minX()); x <= Math.min(maxX, box.maxX()); x++) {
				for (int z = Math.max(minZ, box.minZ()); z <= Math.min(maxZ, box.maxZ()); z++) {
					for (int y = box.minY() + 1; y < box.maxY() - 1; y++) {
						if (!level.getBlockState(at.set(x, y, z)).isAir() || !level.getBlockState(at.set(x, y + 1, z)).isAir()) continue;
						BlockState below = level.getBlockState(at.set(x, y - 1, z));
						if (!below.isFaceSturdy(level, at, net.minecraft.core.Direction.UP) || !below.getFluidState().isEmpty()) continue;
						long score = (long) (y - this.layout.cityMinY()) * 10000 + Math.abs(x - midX) + Math.abs(z - midZ);
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
		int lowest = this.layout.hallBottom(0) + this.layout.hallHeight + 1;
		for (Map.Entry<Structure, StructureStart> entry : List.copyOf(centerChunk.getAllStarts().entrySet())) {
			StructureStart start = entry.getValue();
			if (!start.isValid()) continue;
			// tall layouts test the pieces themselves: the start's box is inflated by 12 for terrain
			// adaptation, so vanilla's lowest trial chambers (pieces down to the frame's padding) would go
			int minY = this.layout.id >= 2 ? piecesMinY(start) : start.getBoundingBox().minY();
			if (minY < lowest && !(this.layout.id >= 2 && this.keepsLowStart(registryAccess, entry.getKey()))) {
				centerChunk.setStartForStructure(entry.getKey(), StructureStart.INVALID_START);
			}
		}

		if (level != Level.OVERWORLD) return;
		if (centerChunk.getPos().equals(this.layout.cityChunk(this.bounds))) {
			// the city JSON's absolute start height (-1280) moves to this layout's city layer
			WorldGenShift.withOverride(this.layout.cityAnchorDelta, () -> {
				this.startDeepStructure(DEEP_CITY, null, null, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
				return null;
			});
		}
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) {
			ResourceKey<Structure> village = this.layout.layer(layer).villageStructure();
			if (village != null && centerChunk.getPos().equals(this.layout.villageChunk(state.getLevelSeed(), layer, this.bounds))) {
				int ground = this.layout.forestFloor(layer) + 1; // first free block above the flattened forest floor
				this.startDeepStructure(village, ground, null, registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
			}
		}
		if (centerChunk.getPos().equals(this.layout.pyramidChunk(state.getLevelSeed(), this.bounds))) {
			this.startDeepPyramid(registryAccess, state.getLevelSeed(), structureManager, centerChunk);
		}
		if (this.layout.id >= 2) this.createTallStarts(registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
	}

	// ------------------------------------------------------------------ tall layouts: the extra deep starts

	private volatile @Nullable List<DeepStarts.Planned> planned;

	/** The hand-placed starts (pure function of seed and bounds), with their footprints. */
	private List<DeepStarts.Planned> planned(long seed, StructureTemplateManager templates, RegistryAccess registryAccess, @Nullable Integer vaultExitZ) {
		List<DeepStarts.Planned> list = this.planned;
		if (list == null) {
			synchronized (this) {
				list = this.planned;
				if (list == null) this.planned = list = DeepStarts.plan(seed, this.bounds, this.layout, this.terrain(seed), templates, registryAccess, vaultExitZ);
			}
		}
		return list;
	}

	/** The {@code start_height} in deep_trial_vault.json. */
	private static final int VAULT_JSON_START_Y = 385;

	/** Chunk where the Trial Vault starts: the one holding (centre x, vault z). */
	public ChunkPos vaultChunk(long seed) {
		return new ChunkPos(this.bounds.centerX() >> 4, DeepStarts.vaultZ(seed, this.bounds) >> 4);
	}

	private void createTallStarts(
		RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager, ChunkAccess chunk,
		StructureTemplateManager templates, ResourceKey<Level> level
	) {
		long seed = state.getLevelSeed();
		ChunkPos pos = chunk.getPos();
		var structures = registryAccess.lookupOrThrow(Registries.STRUCTURE);

		// surface villages: bigger (jigsaw hooks) and always with a farm
		VillageFarms.Ground surface = VillageFarms.surface(this, chunk.getHeightAccessorForGeneration(), state.randomState());
		for (Map.Entry<Structure, StructureStart> entry : List.copyOf(chunk.getAllStarts().entrySet())) {
			StructureStart start = entry.getValue();
			if (!start.isValid() || isDeepKey(registryAccess, entry.getKey()) || !VillageFarms.isVillage(start)) continue;
			StructureStart farmed = VillageFarms.ensure(start, registryAccess, templates, this.bounds, seed, surface);
			if (farmed != start) chunk.setStartForStructure(entry.getKey(), farmed);
		}

		// hand-placed starts (the pyramid has its own code); the layer under the vault avoids its exit
		VaultChoice vaultChoice = this.layout.vaultLayer >= 1 ? this.vaultChoice(registryAccess, state, templates, level) : null;
		List<DeepStarts.Planned> plans = this.planned(seed, templates, registryAccess, vaultChoice == null ? null : vaultChoice.plan().ez);
		for (DeepStarts.Planned plan : plans) {
			if (!plan.chunk().equals(pos) || plan.key().equals(DEEP_PYRAMID)) continue;
			Structure structure = structures.getValue(plan.key());
			List<StructurePiece> pieces = plan.pieces().get();
			if (structure == null || pieces.isEmpty()) {
				AntCraft.LOGGER.warn("AntCraft: {} not placed ({})", plan.key().identifier(), structure == null ? "structure missing" : "no site");
				continue;
			}
			StructureStart previous = structureManager.getStartForStructure(structure, chunk);
			StructureStart start = new StructureStart(structure, pos, previous != null ? previous.getReferences() : 0, new PiecesContainer(pieces));
			structureManager.setStartForStructure(structure, start, chunk);
			AntCraft.LOGGER.info("AntCraft: {} placed, bounds {}", plan.key().identifier(), start.getBoundingBox());
		}

		// projected jigsaw copies on the forest floor (trail ruins, camps, the outpost)
		Object[][] projected = {{"deep_trail_ruins", DeepStarts.TRAIL_RUINS, 8600L}, {"deep_abandoned_camp_dappled", DeepStarts.CAMP_DAPPLED, 8700L},
			{"deep_abandoned_camp_pale", DeepStarts.CAMP_PALE, 8800L}, {"deep_pillager_outpost", DeepStarts.OUTPOST, 8900L}};
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) {
			for (Object[] p : projected) {
				if (!this.layout.layer(layer).extraStructures().contains((String) p[0])) continue;
				if (!pos.equals(DeepStarts.projectedChunk(seed, this.bounds, this.layout, layer, (Long) p[2]))) continue;
				@SuppressWarnings("unchecked")
				ResourceKey<Structure> key = (ResourceKey<Structure>) p[1];
				this.startDeepStructure(key, this.layout.forestFloor(layer) + 1, null, registryAccess, state, structureManager, chunk, templates, level);
			}
		}

		// mineshafts in the cave bands
		for (int layer = 1; layer <= this.layout.deepLayers; layer++) {
			if (this.layout.layer(layer).mineshaft() == LayerSpec.Mineshaft.NONE || !pos.equals(DeepStarts.mineshaftChunk(seed, this.bounds, layer))) continue;
			Structure structure = structures.getValue(DeepStarts.mineshaft(layer));
			if (structure == null) continue;
			List<BoundingBox> footprints = new ArrayList<>();
			for (DeepStarts.Planned plan : plans) if (plan.layer() == layer) footprints.addAll(plan.footprint());
			List<StructurePiece> pieces = DeepStarts.buildMineshaft(seed, this.bounds, this.layout, this.terrain(seed), layer, footprints);
			if (pieces.isEmpty()) continue;
			StructureStart previous = structureManager.getStartForStructure(structure, chunk);
			StructureStart start = new StructureStart(structure, pos, previous != null ? previous.getReferences() : 0, new PiecesContainer(pieces));
			structureManager.setStartForStructure(structure, start, chunk);
			AntCraft.LOGGER.info("AntCraft: {} placed, {} pieces, bounds {}", DeepStarts.mineshaft(layer).identifier(), pieces.size(), start.getBoundingBox());
		}

		// the Trial Vault
		if (this.layout.vaultLayer >= 1 && pos.equals(this.vaultChunk(seed))) this.startVault(registryAccess, state, structureManager, chunk, templates, level);
	}

	/** The chosen Trial Vault: its pieces (final coordinates), plan, and which try it was. */
	public record VaultChoice(List<StructurePiece> pieces, VaultPlan plan, int tryIndex, int grade, long millis) {}

	private volatile @Nullable VaultChoice vaultChoice;
	private volatile boolean vaultChosen;

	/** Vault tries: best of 8, of up to 16 while no try grows both ways (3.5.6 tuning), of up to 32 while none has an entrance. */
	static final int VAULT_TRIES = 16, VAULT_MAX_TRIES = 32;

	/**
	 * The Trial Vault (spec 3.5.2), a pure function of seed, bounds and layout, computed once per
	 * generator: the best of the tries, or null if none generated. The hand-placed starts of the
	 * layer below need it (they avoid the vault exit's fall line), so it is chosen before them.
	 */
	public @Nullable VaultChoice vaultChoice(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureTemplateManager templates, ResourceKey<Level> level) {
		if (this.vaultChosen) return this.vaultChoice;
		synchronized (this) {
			if (!this.vaultChosen) {
				this.vaultChoice = this.chooseVault(registryAccess, state, templates, level);
				this.vaultChosen = true;
			}
			return this.vaultChoice;
		}
	}

	private @Nullable VaultChoice chooseVault(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureTemplateManager templates, ResourceKey<Level> level) {
		Holder<Structure> holder = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(VaultPlan.KEY).orElse(null);
		if (holder == null) {
			AntCraft.LOGGER.error("AntCraft: structure {} is missing", VaultPlan.KEY.identifier());
			return null;
		}
		if (this.vaultSelection >= 2) return this.chooseVault2(holder, registryAccess, state, templates, level);
		long t0 = System.nanoTime();
		long seed = state.getLevelSeed();
		int margin = vaultMargin(this.bounds);
		StructureStart best = null;
		VaultPlan bestPlan = null;
		long bestScore = Long.MIN_VALUE;
		int chosen = -1;
		for (int i = 0; i < VAULT_MAX_TRIES; i++) {
			// the spec's 8 tries; up to 16 while no try grows both up and down; more only while none has an entrance
			boolean usable = bestPlan != null && VaultPlan.grade(bestPlan) >= 1;
			if (i >= 8 && usable && bestPlan.growsBothWays() || i >= VAULT_TRIES && usable) break;
			StructureStart start = this.vaultTry(holder, registryAccess, state, templates, level, Strip.mix(seed, 9500 + i), this.bounds, margin, vaultRotation(i));
			if (start == null) continue;
			VaultPlan plan = VaultPlan.of(start, this.bounds);
			if (plan == null) continue;
			long score = VaultPlan.score(plan);
			AntCraft.LOGGER.info("AntCraft: trial vault try {}: {} pieces, {} chamber rooms, y {}..{}, grade {}", i, start.getPieces().size(), plan.chamberRooms,
				plan.minY(), plan.maxY(), VaultPlan.grade(plan));
			if (score > bestScore) {
				best = start;
				bestPlan = plan;
				bestScore = score;
				chosen = i;
			}
			if (VaultPlan.grade(plan) == 2) break;
		}
		long millis = (System.nanoTime() - t0) / 1_000_000;
		if (best == null) {
			AntCraft.LOGGER.error("AntCraft: the trial vault failed to generate");
			return null;
		}
		if (!bestPlan.entranceValid) AntCraft.LOGGER.warn("AntCraft: no trial vault try has an entrance column; using the start piece centre {}", bestPlan);
		return new VaultChoice(List.copyOf(best.getPieces()), bestPlan, chosen, VaultPlan.grade(bestPlan), millis);
	}

	/** Vault tries of selection 2 (spec 3.5.6 tuning, phase 6 follow-up). */
	static final int VAULT_TRIES_2 = 32;

	/**
	 * Vault selection 2: the best of {@link #VAULT_TRIES_2} tries by {@link VaultPlan#score2}. In
	 * strips up to 30 wide the jigsaw grows only the corridor spine (the side hallways leave the
	 * strip), so every try has the two end rooms; the tries differ in how many trial spawners stay
	 * inside the strip and how deep the exit room lies, which this picks for. Stops early at a try
	 * that meets every target.
	 */
	private @Nullable VaultChoice chooseVault2(Holder<Structure> holder, RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureTemplateManager templates,
		ResourceKey<Level> level) {
		long t0 = System.nanoTime();
		long seed = state.getLevelSeed();
		int margin = vaultMargin(this.bounds);
		StructureStart best = null;
		VaultPlan bestPlan = null;
		long bestScore = Long.MIN_VALUE;
		int chosen = -1;
		for (int i = 0; i < VAULT_TRIES_2; i++) {
			StructureStart start = this.vaultTry(holder, registryAccess, state, templates, level, Strip.mix(seed, 9500 + i), this.bounds, margin, vaultRotation(i));
			if (start == null) continue;
			VaultPlan plan = VaultPlan.of(start, this.bounds);
			if (plan == null) continue;
			int spawners = VaultPlan.spawnersInside(start.getPieces(), this.bounds);
			long score = VaultPlan.score2(plan, spawners, this.layout);
			if (score > bestScore) {
				best = start;
				bestPlan = plan;
				bestScore = score;
				chosen = i;
			}
			if (VaultPlan.meetsTargets(plan, spawners, this.layout)) break;
		}
		long millis = (System.nanoTime() - t0) / 1_000_000;
		if (best == null) {
			AntCraft.LOGGER.error("AntCraft: the trial vault failed to generate");
			return null;
		}
		AntCraft.LOGGER.info("AntCraft: trial vault: try {} of {} chosen: {} chamber rooms, {} trial spawners in the strip, exit room {} above the vault floor", chosen,
			VAULT_TRIES_2, bestPlan.chamberRooms, VaultPlan.spawnersInside(best.getPieces(), this.bounds), VaultPlan.exitDepth(bestPlan, this.layout));
		if (!bestPlan.entranceValid) AntCraft.LOGGER.warn("AntCraft: no trial vault try has an entrance column; using the start piece centre {}", bestPlan);
		return new VaultChoice(List.copyOf(best.getPieces()), bestPlan, chosen, VaultPlan.grade(bestPlan), millis);
	}

	/**
	 * The start piece rotation of vault try i: the two that point the start's corridor along the
	 * strip, alternating. (The other two point it at a wall, and then the jigsaw dies after the
	 * start piece in about half the tries; spec 3.5.6 tuning.)
	 */
	static net.minecraft.world.level.block.@Nullable Rotation vaultRotation(int i) {
		if (StructureAudit.enabled() && Integer.getInteger("antcraft.vaultRotation") != null) {
			int r = Integer.getInteger("antcraft.vaultRotation");
			return r < 0 ? null : net.minecraft.world.level.block.Rotation.values()[r & 3];
		}
		return (i & 1) == 0 ? net.minecraft.world.level.block.Rotation.NONE : net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
	}

	/** Jigsaw depth of the vault (the JSON's size is capped at 20 by its codec). */
	static int vaultDepth() {
		int depth = 20;
		if (StructureAudit.enabled()) depth = Integer.getInteger("antcraft.vaultDepth", depth);
		return depth;
	}

	/** {@code M} of spec 3.5.2: how far vault pieces may overhang each strip wall. */
	static int vaultMargin(StripBounds bounds) {
		int margin = Math.clamp((30 - bounds.width()) / 2, 0, 6);
		if (StructureAudit.enabled()) margin = Integer.getInteger("antcraft.vaultMargin", margin); // tuning runs only (spec 3.5.6)
		return margin;
	}

	/**
	 * One vault try: the jigsaw grown from a start piece in the middle of the vault's jigsaw space,
	 * kept inside the strip (plus the overhang) and moved onto (centre x, vault z). Null if the
	 * jigsaw failed. {@code bounds} is the real strip's except in tuning runs.
	 */
	public @Nullable StructureStart vaultTry(
		Holder<Structure> holder, RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureTemplateManager templates, ResourceKey<Level> level,
		long trySeed, StripBounds bounds, int margin, net.minecraft.world.level.block.@Nullable Rotation rotation
	) {
		int vaultZ = DeepStarts.vaultZ(state.getLevelSeed(), bounds);
		ChunkPos chunkPos = new ChunkPos(bounds.centerX() >> 4, vaultZ >> 4);
		int vault = this.layout.vaultLayer;
		int minY = this.layout.layerBottom(vault) + 2, maxY = this.layout.layerTop(vault) - 6;
		LevelHeightAccessor space = LevelHeightAccessor.create(minY, maxY - minY + 1);
		WorldGenShift.VaultContext context = new WorldGenShift.VaultContext(bounds, vaultZ, margin, maxY, rotation, vaultDepth());
		// the JSON's absolute start height (385) moves to the layout's vault start (no frame shift: it is a real y)
		StructureStart start = WorldGenShift.withVault(context, () -> WorldGenShift.withOverride(this.layout.vaultStartY - VAULT_JSON_START_Y, () -> holder.value().generate(
			holder, level, registryAccess, this, this.biomeSource, state.randomState().createClimateSampler(SamplerContext.EMPTY_UNCACHED), state.randomState(),
			templates, trySeed, chunkPos, 0, space, biome -> true
		)));
		if (!start.isValid()) return null;
		BoundingBox centre = start.getPieces().getFirst().getBoundingBox();
		int dx = bounds.centerX() - (centre.minX() + centre.maxX()) / 2, dz = vaultZ - (centre.minZ() + centre.maxZ()) / 2;
		for (StructurePiece piece : start.getPieces()) piece.move(dx, 0, dz);
		return new StructureStart(holder.value(), chunkPos, 0, new PiecesContainer(start.getPieces()));
	}

	/** Places the chosen vault in its start chunk. */
	private void startVault(
		RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager, ChunkAccess chunk,
		StructureTemplateManager templates, ResourceKey<Level> level
	) {
		Structure structure = registryAccess.lookupOrThrow(Registries.STRUCTURE).getValue(VaultPlan.KEY);
		VaultChoice choice = this.vaultChoice(registryAccess, state, templates, level);
		if (structure == null || choice == null) return;
		StructureStart previous = structureManager.getStartForStructure(structure, chunk);
		int references = previous != null ? previous.getReferences() : 0;
		StructureStart start = new StructureStart(structure, chunk.getPos(), references, new PiecesContainer(choice.pieces()));
		structureManager.setStartForStructure(structure, start, chunk);
		AntCraft.LOGGER.info("AntCraft: {} placed (try {}, grade {}, chosen in {} ms): {}", VaultPlan.KEY.identifier(), choice.tryIndex(), choice.grade(), choice.millis(), choice.plan());
	}

	/**
	 * Tall layouts: vanilla starts that sit at a placeholder height until they are placed (and are
	 * then re-seated on the real surface or sea floor), and our own deep starts, survive the
	 * "below the first hall" filter.
	 */
	private static final java.util.Set<net.minecraft.world.level.levelgen.structure.StructureType<?>> PLACEHOLDER_TYPES = java.util.Set.of(
		net.minecraft.world.level.levelgen.structure.StructureType.DESERT_PYRAMID, net.minecraft.world.level.levelgen.structure.StructureType.JUNGLE_TEMPLE,
		net.minecraft.world.level.levelgen.structure.StructureType.SWAMP_HUT, net.minecraft.world.level.levelgen.structure.StructureType.IGLOO,
		net.minecraft.world.level.levelgen.structure.StructureType.SHIPWRECK, net.minecraft.world.level.levelgen.structure.StructureType.OCEAN_RUIN,
		net.minecraft.world.level.levelgen.structure.StructureType.BURIED_TREASURE
	);

	private static int piecesMinY(StructureStart start) {
		int minY = Integer.MAX_VALUE;
		for (StructurePiece piece : start.getPieces()) minY = Math.min(minY, piece.getBoundingBox().minY());
		return minY;
	}

	private boolean keepsLowStart(RegistryAccess registryAccess, Structure structure) {
		if (PLACEHOLDER_TYPES.contains(structure.type())) return true;
		Identifier id = registryAccess.lookupOrThrow(Registries.STRUCTURE).getKey(structure);
		return id != null && id.getNamespace().equals(AntCraft.MOD_ID);
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
		DesertPyramidPiece piece = DeepStarts.buildPyramid(seed, this.bounds, this.layout, pos);
		StructureStart previous = structureManager.getStartForStructure(structure, chunk);
		StructureStart start = new StructureStart(structure, pos, previous != null ? previous.getReferences() : 0, new PiecesContainer(List.of(piece)));
		structureManager.setStartForStructure(structure, start, chunk);
		AntCraft.LOGGER.info("AntCraft: {} placed facing {}, bounds {}", DEEP_PYRAMID.identifier(), piece.getOrientation(), start.getBoundingBox());
	}

	/** Tries per projected deep start in tall layouts (see {@link #startDeepStructure}). */
	static final int DEEP_START_TRIES = 4;

	/**
	 * Starts one of our deep structures in this chunk. They have no structure sets, so the game would
	 * never place them on its own. {@code ground} (if set) is reported as the surface height while
	 * the structure is laid out.
	 */
	private void startDeepStructure(
		ResourceKey<Structure> key,
		@Nullable Integer ground,
		@Nullable LevelHeightAccessor heightAccessor,
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
		// Tall layouts: projected deep starts (villages, ruins, camps, outpost) get a few tries; a try
		// whose centre piece is wider than the strip is only kept if no other fits, and villages keep
		// the try with the most pieces. Layout 1 keeps its single try (its worlds must not change).
		boolean tall = this.layout.id >= 2 && ground != null;
		int tries = tall ? DEEP_START_TRIES : 1;
		StructureStart start = StructureStart.INVALID_START;
		long bestScore = Long.MIN_VALUE;
		for (int t = 0; t < tries; t++) {
			long seed = t == 0 ? state.getLevelSeed() : Strip.mix(state.getLevelSeed(), 9150 + t);
			DEEP_GROUND.set(ground);
			StructureStart attempt;
			try {
				attempt = structure.generate(
				holder,
				level,
				registryAccess,
				this,
				this.biomeSource,
				randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED),
				randomState,
				structureTemplateManager,
				seed,
				chunk.getPos(),
				previous != null ? previous.getReferences() : 0,
				heightAccessor != null ? heightAccessor : chunk,
				biome -> true
			);
			} finally {
				DEEP_GROUND.remove();
			}
			if (!attempt.isValid()) continue;
			if (ground != null) {
				// The jigsaw start sits at the chunk corner and can rotate away from the narrow strip.
				// Shift the whole village sideways so its centre piece is in the middle of the strip.
				BoundingBox centre = attempt.getPieces().getFirst().getBoundingBox();
				int dx = this.bounds.centerX() - (centre.minX() + centre.maxX()) / 2;
				for (StructurePiece piece : attempt.getPieces()) piece.move(dx, 0, 0);
			}
			if (!tall) {
				start = attempt;
				break;
			}
			BoundingBox centre = attempt.getPieces().getFirst().getBoundingBox();
			boolean fits = centre.minX() >= this.bounds.minX() && centre.maxX() <= this.bounds.maxX();
			boolean village = VillageFarms.isVillage(attempt);
			boolean farm = false;
			if (village) {
				// tall layouts: every deep village gets a farm (a try with no room left for one loses)
				attempt = VillageFarms.ensure(attempt, registryAccess, structureTemplateManager, this.bounds, state.getLevelSeed(), VillageFarms.deep(ground - 1), false);
				farm = VillageFarms.hasFarm(attempt);
			}
			long score = (fits ? 1L << 33 : 0L) + (farm ? 1L << 32 : 0L) + (village ? attempt.getPieces().size() : 0);
			if (score > bestScore) {
				bestScore = score;
				start = attempt;
			}
			if (fits && !village) break; // ruins, camps, the outpost: the first try that fits
		}
		if (start.isValid()) {
			structureManager.setStartForStructure(structure, start, chunk);
			if (tall && VillageFarms.isVillage(start) && !VillageFarms.hasFarm(start)) AntCraft.LOGGER.warn("AntCraft: no spot for a farm in the village at {}", chunk.getPos());
			AntCraft.LOGGER.info("AntCraft: {} placed, {} pieces, bounds {}", key.identifier(), start.getPieces().size(), start.getBoundingBox());
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

	// The frame (tall layouts): the vanilla world is reported D blocks higher. Lithium overwrites
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
		Integer ground = DEEP_GROUND.get();
		if (ground != null) return ground;
		return this.vanillaBaseHeight(x, z, type, heightAccessor, randomState);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		int d = this.frameOffset();
		if (d == 0) return this.base.getBaseColumn(x, z, heightAccessor, randomState);
		LevelHeightAccessor shifted = LevelHeightAccessor.create(heightAccessor.getMinY() - d, heightAccessor.getHeight());
		NoiseColumn column = this.base.getBaseColumn(x, z, shifted, randomState);
		// the column covers the vanilla range clamped to the accessor (NoiseSettings.clampToHeightAccessor)
		int minY = Math.max(this.base.getMinY(), shifted.getMinY());
		int height = Math.min(this.base.getMinY() + this.base.getGenDepth(), shifted.getMaxY() + 1) - minY;
		BlockState[] states = new BlockState[Math.max(0, height)];
		for (int i = 0; i < states.length; i++) states[i] = column == null ? DeepTerrain.AIR : column.getBlock(minY + i);
		return new NoiseColumn(minY + d, states);
	}

	@Override
	public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
		this.base.addDebugScreenInfo(result, randomState, feetPos.below(this.frameOffset()), samplerContext);
		int layer = this.layout.layerAt(feetPos.getY());
		String name = layer >= 1 && this.layout.layer(layer).theme() != null ? " (" + this.layout.layer(layer).theme().englishName + ")" : "";
		result.add("AntCraft layer: " + (layer < 0 ? "bedrock" : layer) + name);
	}
}
