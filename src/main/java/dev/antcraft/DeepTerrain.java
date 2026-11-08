package dev.antcraft;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.util.RandomSource;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Generates everything below the vanilla world inside the strip: holed bedrock floors, the open
 * halls above each floor, cave layers with depth-themed stone, underground forests, fluids and ores.
 * All of it is a pure function of (seed, strip bounds, x, y, z), so chunks regenerate identically.
 */
public final class DeepTerrain {
	private record Key(long seed, StripBounds bounds) {}

	private static final Map<Key, DeepTerrain> CACHE = new ConcurrentHashMap<>();

	public static DeepTerrain forSeed(long seed, StripBounds bounds) {
		return CACHE.computeIfAbsent(new Key(seed, bounds), key -> new DeepTerrain(key.seed(), key.bounds()));
	}

	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
	static final BlockState BARRIER = Blocks.BARRIER.defaultBlockState();
	static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
	static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
	static final BlockState WATER = Blocks.WATER.defaultBlockState();
	static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
	static final BlockState GLOWSTONE = Blocks.GLOWSTONE.defaultBlockState();
	static final BlockState SHROOMLIGHT = Blocks.SHROOMLIGHT.defaultBlockState();
	static final BlockState LANTERN = Blocks.LANTERN.defaultBlockState();
	static final BlockState LIGHT_15 = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15);

	/** Base and patch stone of each deep layer (index = layer). */
	private static final BlockState[] BASE_STONE = {
		DEEPSLATE, // 0 unused (vanilla)
		DEEPSLATE,
		DEEPSLATE,
		Blocks.TUFF.defaultBlockState(),
		Blocks.DRIPSTONE_BLOCK.defaultBlockState(),
		DEEPSLATE, // 5: deep dark / ancient city
		Blocks.SANDSTONE.defaultBlockState(), // 6: underground desert
		Blocks.BLACKSTONE.defaultBlockState(),
		DEEPSLATE,
	};
	private static final BlockState[] PATCH_STONE = {
		DEEPSLATE,
		Blocks.TUFF.defaultBlockState(),
		Blocks.TUFF.defaultBlockState(),
		Blocks.CALCITE.defaultBlockState(),
		Blocks.CALCITE.defaultBlockState(),
		Blocks.SCULK.defaultBlockState(), // 5: deep dark
		Blocks.RED_SANDSTONE.defaultBlockState(), // 6: underground desert
		Blocks.MAGMA_BLOCK.defaultBlockState(),
		Blocks.BLACKSTONE.defaultBlockState(),
	};

	private final long seed;
	private final StripBounds bounds;
	private final SimplexNoise cheese;
	private final SimplexNoise cheeseDetail;
	private final SimplexNoise noodleA;
	private final SimplexNoise noodleB;
	private final SimplexNoise patch;
	private final SimplexNoise hills;
	private final SimplexNoise ceiling;
	private final SimplexNoise glow;
	private final SimplexNoise pond;
	private final SimplexNoise pillar;
	private final SimplexNoise fluid;
	private final SimplexNoise mud;

	private DeepTerrain(long seed, StripBounds bounds) {
		this.seed = seed;
		this.bounds = bounds;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 42));
		this.cheese = new SimplexNoise(random);
		this.cheeseDetail = new SimplexNoise(random);
		this.noodleA = new SimplexNoise(random);
		this.noodleB = new SimplexNoise(random);
		this.patch = new SimplexNoise(random);
		this.hills = new SimplexNoise(random);
		this.ceiling = new SimplexNoise(random);
		this.glow = new SimplexNoise(random);
		this.pond = new SimplexNoise(random);
		this.pillar = new SimplexNoise(random);
		this.fluid = new SimplexNoise(random);
		this.mud = new SimplexNoise(random);
	}

	public long seed() {
		return this.seed;
	}

	public StripBounds bounds() {
		return this.bounds;
	}

	// ------------------------------------------------------------------ chunk writes

	static void set(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
		section.setBlockState(lx, y & 15, lz, state, false);
		if (chunk instanceof net.minecraft.world.level.chunk.ProtoChunk && !state.getFluidState().isEmpty()) {
			// raw section writes schedule no fluid ticks: let the chunk tick this fluid once it is
			// loaded (like vanilla's aquifers do), so exposed lava and water actually flow
			chunk.markPosForPostProcessing(new BlockPos(chunk.getPos().getMinBlockX() + lx, y, chunk.getPos().getMinBlockZ() + lz));
		}
	}

	static BlockState get(ChunkAccess chunk, int lx, int y, int lz) {
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
		return section.getBlockState(lx, y & 15, lz);
	}

	// ------------------------------------------------------------------ per-column shape

	/** Layer-distinct 2D noise sample in roughly [-1, 1]. */
	private static double n2(SimplexNoise noise, int layer, double x, double z, double scale) {
		return noise.get(x * scale + layer * 1013.0, z * scale - layer * 719.0);
	}

	/** Surface y of the forest floor in this column. */
	public int forestSurface(int layer, int x, int z) {
		return Strip.forestFloor(layer) + (int) Math.round(n2(this.hills, layer, x, z, 0.045) * 3.0);
	}

	/** Lowest solid block of the forest ceiling in this column. */
	public int forestCeiling(int layer, int x, int z) {
		return Strip.forestFloor(layer) + Strip.FOREST_HEIGHT + (int) Math.round(n2(this.ceiling, layer, x, z, 0.06) * 4.0);
	}

	/** Pond strength; > 0 means this column of the forest floor is water. */
	public double pondValue(int layer, int x, int z) {
		double threshold = layer == 7 ? 0.25 : layer == Strip.DESERT_LAYER ? 0.66 : 0.5; // swamp / rare oases
		return n2(this.pond, layer, x, z, 0.05) - threshold;
	}

	public boolean isPond(int layer, int x, int z) {
		return this.pondValue(layer, x, z) > 0.0;
	}

	/**
	 * Forest floor height in a column, ponds included: pond columns and the columns right next to a
	 * pond sit at the pond level, so ponds get a flat rim at water height (where sugar cane grows).
	 * Village columns are flattened separately during terrain generation.
	 */
	public int surfaceAt(int layer, int x, int z) {
		if (this.isPond(layer, x, z) || this.isPond(layer, x + 1, z) || this.isPond(layer, x - 1, z) || this.isPond(layer, x, z + 1) || this.isPond(layer, x, z - 1)) {
			return pondLevel(layer);
		}
		return this.forestSurface(layer, x, z);
	}

	/** Pond water level: the lowest the rolling forest floor can go, so ponds never spill over. */
	public static int pondLevel(int layer) {
		return Strip.forestFloor(layer) - 3;
	}

	/**
	 * True if (x, z) is within {@code radius} blocks (square) of the hole in the floor above deep
	 * layer {@code layer} (floor layer - 1), i.e. right under where you come down into this layer.
	 */
	public boolean nearHoleAbove(int layer, int x, int z, int radius) {
		if (layer < 1 || layer > Strip.DEEP_LAYERS) return false;
		int k = layer - 1;
		return Math.abs(x - Strip.holeX(this.seed, k, this.bounds)) <= radius && Math.abs(z - Strip.holeZ(this.seed, k, this.bounds)) <= radius;
	}

	private boolean isPillar(int layer, int x, int z) {
		for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
			// never block the way to a hole
			if (Strip.floorTop(k) + 1 == Strip.hallBottom(layer)
				&& Math.abs(x - Strip.holeX(this.seed, k, this.bounds)) <= 2 && Math.abs(z - Strip.holeZ(this.seed, k, this.bounds)) <= 2) {
				return false;
			}
		}
		return n2(this.pillar, layer, x, z, 0.18) > 0.72;
	}

	/** Ground block for a forest layer. */
	private BlockState forestGround(int layer, int x, int z) {
		return switch (layer) {
			case 6 -> Blocks.SAND.defaultBlockState();
			case 7 -> n2(this.mud, layer, x, z, 0.08) > 0.0 ? Blocks.MUD.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
			case 8 -> Blocks.MYCELIUM.defaultBlockState();
			default -> Blocks.GRASS_BLOCK.defaultBlockState();
		};
	}

	private BlockState stoneAt(int layer, int x, int y, int z) {
		double p = this.patch.get(x * 0.05, y * 0.07, z * 0.05);
		double threshold = layer == 7 ? 0.62 : 0.4; // magma is rarer
		return p > threshold ? PATCH_STONE[layer] : BASE_STONE[layer];
	}

	private boolean carvedCave(int layer, int x, int y, int z) {
		double depth = (Strip.VANILLA_MIN_Y - y) / (double) (Strip.VANILLA_MIN_Y - Strip.WORLD_MIN_Y); // 0..1
		double c = this.cheese.get(x * 0.022, y * 0.035, z * 0.022) * 0.75 + this.cheeseDetail.get(x * 0.07, y * 0.09, z * 0.07) * 0.25;
		if (c > 0.48 - depth * 0.18) {
			return true;
		}
		double a = this.noodleA.get(x * 0.03, y * 0.045, z * 0.03);
		if (Math.abs(a) > 0.08) return false;
		double b = this.noodleB.get(x * 0.03, y * 0.045, z * 0.03);
		return Math.abs(b) < 0.08;
	}

	private boolean noodleOnly(int x, int y, int z) {
		double a = this.noodleA.get(x * 0.03, y * 0.045, z * 0.03);
		if (Math.abs(a) > 0.07) return false;
		return Math.abs(this.noodleB.get(x * 0.03, y * 0.045, z * 0.03)) < 0.07;
	}

	/** Small per-block hash in [0, 1). */
	private double hash01(int x, int y, int z, int salt) {
		long h = Strip.mix(this.seed ^ ((long) x * 341873128712L + (long) z * 132897987541L + (long) y * 1664525L), salt);
		return (h >>> 11) * 0x1.0p-53;
	}

	// ------------------------------------------------------------------ filling

	/** Turns the vanilla bedrock noise into the first holed floor and opens the hall above it. */
	public void reshapeVanillaBottom(ChunkAccess chunk, int lx, int lz, int x, int z) {
		for (int y = Strip.floorBase(0); y <= Strip.floorTop(0); y++) {
			set(chunk, lx, y, lz, BEDROCK);
		}
		int hallBottom = Strip.hallBottom(0);
		boolean pillar = this.isPillar(0, x, z);
		for (int y = hallBottom; y < hallBottom + Strip.HALL_HEIGHT; y++) {
			set(chunk, lx, y, lz, pillar ? DEEPSLATE : AIR);
		}
		// Leftover vanilla bedrock specks directly above the hall, and vanilla's deep lava (y <= -55),
		// which would otherwise hang unticked over the hall and pour in at the first block update.
		for (int y = hallBottom + Strip.HALL_HEIGHT; y <= hallBottom + Strip.HALL_HEIGHT + 2; y++) {
			BlockState state = get(chunk, lx, y, lz);
			if (state.is(Blocks.BEDROCK) || !state.getFluidState().isEmpty()) set(chunk, lx, y, lz, DEEPSLATE);
		}
	}

	/** Fills one strip column from the world bottom up to the first floor. */
	public void fillColumn(ChunkAccess chunk, int lx, int lz, int x, int z, List<BoundingBox> cityPieces) {
		for (int y = Strip.WORLD_MIN_Y; y <= Strip.BOTTOM_TOP_Y; y++) {
			set(chunk, lx, y, lz, BEDROCK);
		}
		for (int k = 1; k < Strip.FLOOR_COUNT; k++) {
			for (int y = Strip.floorBase(k); y <= Strip.floorTop(k); y++) {
				set(chunk, lx, y, lz, BEDROCK);
			}
		}
		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			this.fillLayer(chunk, lx, lz, x, z, layer, cityPieces);
		}
	}

	private void fillLayer(ChunkAccess chunk, int lx, int lz, int x, int z, int layer, List<BoundingBox> deepPieces) {
		int bottom = Strip.layerBottom(layer);
		int top = Strip.layerTop(layer);
		int hallTop = bottom + Strip.HALL_HEIGHT - 1;
		boolean pillar = this.isPillar(layer, x, z);

		// Deep structure pieces near this column: villages sit in the forest band, the ancient city
		// (layer 5 only) below it.
		int forestFloor = Strip.forestFloor(layer);
		List<BoundingBox> pieces = new ArrayList<>();
		boolean village = false;
		for (BoundingBox box : deepPieces) {
			if (box.maxY() < Strip.layerBottom(layer) || box.minY() > Strip.layerTop(layer)) continue;
			boolean inForest = box.maxY() >= forestFloor - 12;
			int margin = inForest ? 3 : 2;
			if (x < box.minX() - margin || x > box.maxX() + margin || z < box.minZ() - margin || z > box.maxZ() + margin) continue;
			if (inForest) village = true;
			else if (layer == Strip.CITY_LAYER) pieces.add(box);
		}

		// villages get a flat floor without ponds (it's what their layout was computed against)
		boolean pond = !village && this.isPond(layer, x, z);
		int surface = village ? forestFloor : this.surfaceAt(layer, x, z);
		int ceil = this.forestCeiling(layer, x, z);
		double pondDepth = this.pondValue(layer, x, z);
		BlockState ground = this.forestGround(layer, x, z);
		double glowValue = n2(this.glow, layer, x, z, 0.11);
		// the desert gets a brighter "sky"
		double glowThreshold = layer == Strip.DESERT_LAYER ? 0.35 : 0.55;
		double hangThreshold = layer == Strip.DESERT_LAYER ? 0.6 : 0.75;
		// no noodle tunnels right under or next to pond water, or the pond would drain
		boolean nearPond = pond || this.isPond(layer, x + 1, z) || this.isPond(layer, x - 1, z) || this.isPond(layer, x, z + 1) || this.isPond(layer, x, z - 1);
		int pondLevel = pondLevel(layer);
		double fluidValue = n2(this.fluid, layer, x, z, 0.04);
		BlockState puddle = layer <= 2 ? (fluidValue > 0.0 ? WATER : LAVA) : LAVA;
		boolean puddleColumn = Math.abs(fluidValue) > 0.62;

		// Ancient city: like vanilla's "beard_box" terrain adaptation, the city's pieces (collected
		// above) get their space cleared plus a solid foundation; only around the pieces, so there is
		// no single huge drop through the whole layer.

		boolean below = true; // block below is solid
		for (int y = bottom; y <= top; y++) {
			BlockState state;
			int city = pieces.isEmpty() || y <= hallTop || y >= surface - 10 ? 0 : cityAt(pieces, y);
			if (city == 1) {
				state = AIR;
			} else if (city == 2) {
				state = this.stoneAt(layer, x, y, z);
			} else if (y <= hallTop) {
				state = pillar ? BASE_STONE[layer] : AIR;
			} else if (y >= surface - 4 && y <= surface) {
				if (pond && y == surface) {
					state = WATER;
				} else if (pond && y == surface - 1) {
					state = pondDepth > 0.15 ? WATER : pondBottom(layer);
				} else if (pond && y == surface - 2) {
					state = pondBottom(layer);
				} else if (y == surface) {
					state = ground;
				} else if (layer == Strip.DESERT_LAYER) {
					state = y == surface - 1 ? Blocks.SAND.defaultBlockState() : Blocks.SANDSTONE.defaultBlockState();
				} else {
					state = layer == 7 ? Blocks.MUD.defaultBlockState() : DIRT;
				}
				// tunnels from the caves may open into the forest floor (but not under a pond)
				boolean pondZone = nearPond && y >= pondLevel - 4;
				if (y < surface - 1 && !pondZone && layer != Strip.DESERT_LAYER && this.noodleOnly(x, y, z)) state = AIR;
			} else if (y > surface && y < ceil) {
				state = AIR;
			} else if (y >= ceil && y <= ceil + 2) {
				if (y == ceil && glowValue > glowThreshold) {
					state = layer == 8 ? SHROOMLIGHT : GLOWSTONE;
				} else {
					state = this.stoneAt(layer, x, y, z);
				}
			} else {
				// cave stone
				boolean nearForest = y >= surface - 9 && y <= ceil + 6;
				boolean pondZone = nearPond && y >= pondLevel - 6 && y <= pondLevel + 1;
				boolean carved = pondZone ? false : nearForest ? this.noodleOnly(x, y, z) : this.carvedCave(layer, x, y, z);
				if (carved) {
					state = below && puddleColumn && !nearForest ? puddle : AIR;
				} else {
					state = this.stoneAt(layer, x, y, z);
				}
			}
			set(chunk, lx, y, lz, state);
			below = !state.isAir() && state.getFluidState().isEmpty();
		}

		// hanging glowstone "suns" below the forest ceiling
		boolean underHole = this.nearHoleAbove(layer, x, z, 2);
		if (glowValue > hangThreshold && !underHole) {
			int length = 1 + (int) ((glowValue - hangThreshold) * 16);
			for (int i = 1; i <= length && ceil - i > surface + 6; i++) {
				set(chunk, lx, ceil - i, lz, layer == 8 ? SHROOMLIGHT : GLOWSTONE);
			}
		}
		if (!underHole) this.decorateForestColumn(chunk, lx, lz, x, z, layer, surface, ceil, pond, ground);
	}

	private static BlockState pondBottom(int layer) {
		return layer == Strip.DESERT_LAYER ? Blocks.SAND.defaultBlockState() : Blocks.CLAY.defaultBlockState();
	}

	/** 1 = inside a city piece (air), 2 = foundation under a piece (solid), 0 = not city. */
	private static int cityAt(List<BoundingBox> pieces, int y) {
		int result = 0;
		for (BoundingBox box : pieces) {
			if (y >= box.minY() && y <= box.maxY()) return 1;
			if (y >= box.minY() - 6 && y < box.minY()) result = 2;
		}
		return result;
	}

	private void decorateForestColumn(ChunkAccess chunk, int lx, int lz, int x, int z, int layer, int surface, int ceil, boolean pond, BlockState ground) {
		// glow berries hanging from the jungle ceiling
		if (layer == 4 && this.hash01(x, ceil, z, 7) < 0.12 && get(chunk, lx, ceil - 1, lz).isAir()) {
			int length = 1 + (int) (this.hash01(x, ceil, z, 8) * 5);
			for (int i = 1; i <= length; i++) {
				int y = ceil - i;
				if (!get(chunk, lx, y, lz).isAir() || y <= surface + 4) break;
				boolean berries = this.hash01(x, y, z, 9) < 0.35;
				Block block = i == length ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT;
				set(chunk, lx, y, lz, block.defaultBlockState().setValue(CaveVines.BERRIES, berries));
			}
		}

	}

	/** Water at the same height right next to this column (so sugar cane can survive). */
	private boolean waterNextTo(WorldGenLevel level, int x, int z, int surface) {
		int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] o : offsets) {
			int nx = x + o[0], nz = z + o[1];
			if (this.bounds.inside(nx, nz) && level.getFluidState(new BlockPos(nx, surface, nz)).is(net.minecraft.tags.FluidTags.WATER)) return true;
		}
		return false;
	}

	/**
	 * Ground plants and the invisible light grid of the forests. Runs during decoration, after the
	 * trees: lights and petals/carpets are not tree-replaceable and would stop trees from growing.
	 */
	private static final int[][] LIGHT_SPOTS = {{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
	private static final int[] LIGHT_HEIGHTS = {2, 3, 4, 1};

	/**
	 * One invisible light-15 block of the forest light grid (every 4 blocks, 2 above the ground):
	 * enough for light 9+ on every open ground block, so saplings and crops grow. A spot taken by a
	 * tree or a house moves to the nearest free one instead of leaving a dark patch.
	 */
	private void placeGridLight(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int ground) {
		for (int dy : LIGHT_HEIGHTS) {
			for (int[] o : LIGHT_SPOTS) {
				int px = x + o[0], pz = z + o[1];
				if (!this.bounds.inside(px, pz)) continue;
				if (level.getBlockState(at.set(px, ground + dy, pz)).isAir()) {
					level.setBlock(at, LIGHT_15, Block.UPDATE_CLIENTS);
					return;
				}
			}
		}
	}

	public void decorateForests(WorldGenLevel level, ChunkAccess chunk, List<BoundingBox> deepPieces) {
		ChunkPos pos = chunk.getPos();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		this.placeWallVines(level, pos, deepPieces); // before the light grid, which would take their air
		for (int x = Math.max(pos.getMinBlockX(), this.bounds.minX()); x <= Math.min(pos.getMaxBlockX(), this.bounds.maxX()); x++) {
			for (int z = Math.max(pos.getMinBlockZ(), this.bounds.minZ()); z <= Math.min(pos.getMaxBlockZ(), this.bounds.maxZ()); z++) {
				for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
					int surface = this.surfaceAt(layer, x, z);
					BlockState plant = this.plantFor(level, layer, x, z, surface);
					if (plant != null && plant.is(Blocks.CACTUS)) {
						// cactus breaks next to anything solid: only on open sand
						boolean open = true;
						for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
							for (int dy = 1; dy <= 3; dy++) open &= level.getBlockState(at.set(x + o[0], surface + dy, z + o[1])).isAir();
						}
						int height = 1 + (int) (this.hash01(x, surface + 1, z, 13) * 3);
						for (int i = 1; open && i <= height && level.getBlockState(at.set(x, surface + i, z)).isAir(); i++) {
							level.setBlock(at, plant, Block.UPDATE_CLIENTS);
						}
					} else if (plant != null && plant.is(Blocks.SUGAR_CANE)) {
						int height = 1 + (int) (this.hash01(x, surface + 1, z, 12) * 3);
						for (int i = 1; i <= height && level.getBlockState(at.set(x, surface + i, z)).isAir(); i++) {
							level.setBlock(at, plant, Block.UPDATE_CLIENTS);
						}
					} else if (plant != null) {
						level.setBlock(at.set(x, surface + 1, z), plant, Block.UPDATE_CLIENTS);
					}
					if (Math.floorMod(x, 4) == 1 && Math.floorMod(z, 4) == 1) {
						// villages stand on a flattened floor, not the natural forest surface
						int ground = UndergroundForest.inVillage(deepPieces, layer, x, z, 3) ? Strip.forestFloor(layer) : surface;
						this.placeGridLight(level, at, x, z, ground);
					}
				}
			}
		}
	}

	/** Wall vine curtains: every {@code VINE_SPACING} blocks along z, {@code VINE_WIDTH} wide. */
	private static final int VINE_SPACING = 8, VINE_WIDTH = 2;

	/**
	 * Climbable vine curtains on the inside of both long walls in every forest band, hanging on the
	 * wall's bedrock from just under the forest ceiling down to the floor: a way back up to the hall
	 * (and a slow way down) without a ladder. Only placed in air, never inside a village.
	 */
	private void placeWallVines(WorldGenLevel level, ChunkPos pos, List<BoundingBox> deepPieces) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int minZ = Math.max(pos.getMinBlockZ(), this.bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), this.bounds.maxZ());
		for (int x : new int[] {this.bounds.minX(), this.bounds.maxX()}) {
			if (x < pos.getMinBlockX() || x > pos.getMaxBlockX()) continue;
			// the wall is to the west of the strip's first column and to the east of its last one
			BlockState vine = Blocks.VINE.defaultBlockState().setValue(x == this.bounds.minX() ? VineBlock.WEST : VineBlock.EAST, true);
			for (int z = minZ; z <= maxZ; z++) {
				if (Math.floorMod(z - this.bounds.minZ() - 3, VINE_SPACING) >= VINE_WIDTH) continue;
				for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
					if (UndergroundForest.inVillage(deepPieces, layer, x, z, 1)) continue;
					int surface = this.surfaceAt(layer, x, z);
					for (int y = this.forestCeiling(layer, x, z) - 1; y > surface; y--) {
						if (level.getBlockState(at.set(x, y, z)).isAir()) level.setBlock(at, vine, Block.UPDATE_CLIENTS);
					}
				}
			}
		}
	}

	private @Nullable BlockState plantFor(WorldGenLevel level, int layer, int x, int z, int surface) {
		if (this.isPond(layer, x, z)) return null;
		BlockState ground = this.forestGround(layer, x, z);
		if (!level.getBlockState(new BlockPos(x, surface, z)).equals(ground) || !level.getBlockState(new BlockPos(x, surface + 1, z)).isAir()) return null;
		double r = this.hash01(x, surface + 1, z, 11);
		if (layer != 8 && (ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.SAND)) && r < 0.25 && waterNextTo(level, x, z, surface)) {
			return Blocks.SUGAR_CANE.defaultBlockState();
		}
		return switch (layer) {
			case 6 -> r < 0.04 ? Blocks.DEAD_BUSH.defaultBlockState() : r < 0.12 ? Blocks.SHORT_DRY_GRASS.defaultBlockState() : r < 0.135 ? Blocks.CACTUS.defaultBlockState() : null;
			case 7 -> ground.is(Blocks.GRASS_BLOCK) && r < 0.3 ? Blocks.SHORT_GRASS.defaultBlockState() : null;
			case 8 -> r < 0.05 ? Blocks.BROWN_MUSHROOM.defaultBlockState() : r < 0.08 ? Blocks.RED_MUSHROOM.defaultBlockState() : null;
			case 2 -> r < 0.25 ? Blocks.FERN.defaultBlockState() : r < 0.35 ? Blocks.SHORT_GRASS.defaultBlockState() : r < 0.37 ? Blocks.SWEET_BERRY_BUSH.defaultBlockState() : null;
			case 5 -> r < 0.2 ? Blocks.SHORT_GRASS.defaultBlockState() : r < 0.3 ? Blocks.PINK_PETALS.defaultBlockState() : null;
			default -> r < 0.3 ? Blocks.SHORT_GRASS.defaultBlockState() : r < 0.33 ? Blocks.POPPY.defaultBlockState() : r < 0.36 ? Blocks.DANDELION.defaultBlockState()
				: r < 0.37 ? Blocks.CORNFLOWER.defaultBlockState() : null;
		};
	}

	// ------------------------------------------------------------------ holes

	/** Cuts the single hole through each floor that passes through this chunk, plus a ladder shaft and landing room. */
	public void cutHoles(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
			int hx = Strip.holeX(this.seed, k, this.bounds);
			int hz = Strip.holeZ(this.seed, k, this.bounds);
			int floorBase = Strip.floorBase(k);
			int floorTop = Strip.floorTop(k);
			int layerBelow = k + 1;

			// glowing markers around the hole, embedded in the floor surface
			for (int dx = -1; dx <= 1; dx += 2) {
				for (int dz = -1; dz <= 1; dz += 2) {
					setIfInChunk(chunk, pos, hx + dx, floorTop, hz + dz, GLOWSTONE);
				}
			}

			// landing room right under the floor: 3x3x3 air with a stone floor and one glowstone
			int roomTop = floorBase - 1;
			int roomBottom = floorBase - 3;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int y = roomBottom; y <= roomTop; y++) {
						setIfInChunk(chunk, pos, hx + dx, y, hz + dz, AIR);
					}
					setIfInChunk(chunk, pos, hx + dx, roomBottom - 1, hz + dz, dx == 1 && dz == -1 ? GLOWSTONE : BASE_STONE[layerBelow]);
				}
			}

			// ladder only through the bedrock floor itself; it hangs on the bedrock to its south
			BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
			for (int y = floorBase; y <= floorTop; y++) {
				setIfInChunk(chunk, pos, hx, y, hz, ladder);
			}

			// light up the hall where you step onto the ladder: a glowstone patch in the hall ceiling
			// above the hole and four lanterns around it
			int ceiling = floorTop + 1 + Strip.HALL_HEIGHT;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					setIfInChunk(chunk, pos, hx + dx, ceiling, hz + dz, GLOWSTONE);
				}
			}
			for (int dx = -2; dx <= 2; dx += 4) {
				for (int dz = -2; dz <= 2; dz += 4) {
					setIfInChunk(chunk, pos, hx + dx, floorTop + 1, hz + dz, LANTERN);
				}
			}
			// water to land in, in the forest floor straight below the hole
			this.cutHolePool(chunk, pos, layerBelow, hx, hz);

			// Sealed holes: floor 4 until the player brings the Nether Key, floor 7 until the Ancient
			// Key, the boss-room floors until their guardian dies. A gate that is switched off in the
			// config leaves the plain ladder.
			AntCraftConfig config = AntCraftConfig.get();
			BlockState seal = null;
			if (k == Strip.SEALED_FLOOR && config.netherKeyGate) {
				seal = ModContent.NETHER_SEAL.defaultBlockState();
			} else if (k == Strip.ANCIENT_SEALED_FLOOR && config.ancientKeyGate) {
				seal = ModContent.ANCIENT_SEAL.defaultBlockState();
			} else if (Strip.isGuardianFloor(k) && config.guardians) {
				seal = ModContent.GUARDIAN_SEAL.defaultBlockState();
				this.buildArena(chunk, pos, k, hz);
			}
			if (seal != null) setIfInChunk(chunk, pos, hx, floorTop, hz, seal);
			// keep the hall right above the hole open
			for (int y = floorTop + 1; y <= floorTop + 3; y++) {
				setIfInChunk(chunk, pos, hx, y, hz, AIR);
			}
		}
	}

	/**
	 * The boss room in the hall above floor {@code k} (see {@link ArenaManager}): a wall across the
	 * strip on each side of the hole, with a 3-wide doorway in the middle, and a closed ceiling so
	 * caves above the hall don't lead around the walls.
	 */
	private void buildArena(ChunkAccess chunk, ChunkPos pos, int k, int hz) {
		if (hz + ArenaManager.WALL_OFFSET < pos.getMinBlockZ() || hz - ArenaManager.WALL_OFFSET > pos.getMaxBlockZ()) return;
		int floorTop = Strip.floorTop(k);
		int ceiling = floorTop + Strip.HALL_HEIGHT + 1;
		int top = floorTop + Strip.HALL_HEIGHT + ArenaManager.WALL_EXTRA_HEIGHT;
		BlockState wall = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
		BlockState stone = BASE_STONE[k];
		for (int x = this.bounds.minX(); x <= this.bounds.maxX(); x++) {
			for (int side = -1; side <= 1; side += 2) {
				int z = hz + side * ArenaManager.WALL_OFFSET;
				for (int y = floorTop + 1; y <= top; y++) {
					boolean door = ArenaManager.isDoorway(this.bounds, k, x, y);
					setIfInChunk(chunk, pos, x, y, z, door ? AIR : wall);
					if (door) {
						// keep the way through clear on both sides (a pillar could stand there)
						setIfInChunk(chunk, pos, x, y, z - 1, AIR);
						setIfInChunk(chunk, pos, x, y, z + 1, AIR);
					}
				}
			}
			for (int z = Math.max(hz - ArenaManager.WALL_OFFSET + 1, pos.getMinBlockZ()); z <= Math.min(hz + ArenaManager.WALL_OFFSET - 1, pos.getMaxBlockZ()); z++) {
				if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || !this.bounds.inside(x, z)) continue;
				int lx = x - pos.getMinBlockX(), lz = z - pos.getMinBlockZ();
				for (int y = ceiling; y <= top; y++) {
					BlockState state = get(chunk, lx, y, lz);
					if (state.isAir() || !state.getFluidState().isEmpty()) set(chunk, lx, y, lz, stone);
				}
				// a few lights in the ceiling
				if (Math.floorMod(x - this.bounds.minX(), 6) == 3 && Math.floorMod(z - hz, 6) == 3) set(chunk, lx, ceiling, lz, GLOWSTONE);
			}
		}
	}

	/**
	 * A 3x3, 2-deep pool in a forest floor, centred under the hole of the floor above: digging
	 * straight down from the landing room drops you into water instead of onto the ground. The
	 * columns around it get a rim so the water stays in. Trees and plants skip water columns.
	 */
	private void cutHolePool(ChunkAccess chunk, ChunkPos pos, int layer, int hx, int hz) {
		int top = this.surfaceAt(layer, hx, hz);
		boolean desert = layer == Strip.DESERT_LAYER;
		BlockState rim = desert ? Blocks.SANDSTONE.defaultBlockState() : DIRT;
		BlockState bottom = desert ? Blocks.SANDSTONE.defaultBlockState() : Blocks.CLAY.defaultBlockState();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int x = hx + dx, z = hz + dz;
				if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ() || !this.bounds.inside(x, z)) continue;
				int lx = x - pos.getMinBlockX(), lz = z - pos.getMinBlockZ();
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
					set(chunk, lx, top - 2, lz, bottom);
					set(chunk, lx, top - 1, lz, WATER);
					set(chunk, lx, top, lz, WATER);
					// higher ground in this column (rolling hills) is cut away down to the water
					for (int y = top + 1; y <= Math.max(top, this.surfaceAt(layer, x, z)) + 1; y++) set(chunk, lx, y, lz, AIR);
				} else {
					for (int y = top - 1; y <= top; y++) {
						BlockState state = get(chunk, lx, y, lz);
						if (state.isAir() || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) set(chunk, lx, y, lz, rim);
					}
				}
			}
		}
	}

	/**
	 * Fluids that decoration put directly above the floor-0 hall ceiling (lush cave pools, lava).
	 * Covers the whole 3x3 region decoration may write to, so whichever chunk decorates last cleans
	 * up after its neighbours too.
	 */
	public void sealAboveFirstHall(WorldGenLevel level, ChunkPos center) {
		int from = Strip.hallBottom(0) + Strip.HALL_HEIGHT;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		// y -55..-53 always solid; y -52..-51 only if it is fluid that could flow into the band
		for (int cx = center.x() - 1; cx <= center.x() + 1; cx++) {
			for (int cz = center.z() - 1; cz <= center.z() + 1; cz++) {
				ChunkPos pos = new ChunkPos(cx, cz);
				if (!this.bounds.chunkOverlaps(pos)) continue;
				ChunkAccess chunk = level.getChunk(cx, cz);
				for (int x = Math.max(pos.getMinBlockX(), this.bounds.minX()); x <= Math.min(pos.getMaxBlockX(), this.bounds.maxX()); x++) {
					for (int z = Math.max(pos.getMinBlockZ(), this.bounds.minZ()); z <= Math.min(pos.getMaxBlockZ(), this.bounds.maxZ()); z++) {
						for (int y = from; y <= from + 4; y++) {
							if (!chunk.getBlockState(at.set(x, y, z)).getFluidState().isEmpty()) chunk.setBlockState(at, DEEPSLATE);
						}
					}
				}
			}
		}
	}

	/** Leaves are recomputed in this y band around each deep forest (it covers the forest's open air with a margin). */
	static final int LEAF_BAND_BELOW = 8, LEAF_BAND_ABOVE = Strip.FOREST_HEIGHT + 12;

	/**
	 * Recomputes the leaf distances in the deep forests after decoration, like vanilla does when a
	 * neighbour changes: a breadth-first search (6 neighbours) from every block in
	 * {@code #prevents_nearby_leaf_decay} (logs) through leaves. Each leaf gets its real distance
	 * 1..6; leaves left at 7 (cut off from every log by the walls, clipping or a village piece) are
	 * removed, so they never decay into sticks and saplings on their own. Everything else keeps
	 * decaying normally once you chop its tree.
	 *
	 * <p>Covers the 3x3 chunk region decoration may write to. Near a side of that region where the
	 * strip goes on, a log may stand just outside the region: there a leaf only gets closer (never
	 * further) and is never removed; the neighbouring chunk's own pass covers it fully later.
	 */
	public void recomputeLeaves(WorldGenLevel level, ChunkPos center) {
		StripBounds b = this.bounds;
		int minX = Math.max((center.x() - 1) << 4, b.minX()), maxX = Math.min(((center.x() + 1) << 4) + 15, b.maxX());
		int minZ = Math.max((center.z() - 1) << 4, b.minZ()), maxZ = Math.min(((center.z() + 1) << 4) + 15, b.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		// sides of the region where the strip continues (a log could stand beyond them)
		boolean openMinX = minX > b.minX(), openMaxX = maxX < b.maxX(), openMinZ = minZ > b.minZ(), openMaxZ = maxZ < b.maxZ();
		int nx = maxX - minX + 1, nz = maxZ - minZ + 1, ny = LEAF_BAND_BELOW + LEAF_BAND_ABOVE + 1;
		ChunkAccess[] chunks = new ChunkAccess[9];
		for (int cx = -1; cx <= 1; cx++) {
			for (int cz = -1; cz <= 1; cz++) {
				ChunkPos pos = new ChunkPos(center.x() + cx, center.z() + cz);
				if (b.chunkOverlaps(pos)) chunks[(cx + 1) * 3 + cz + 1] = level.getChunk(pos.x(), pos.z());
			}
		}

		byte[] dist = new byte[nx * nz * ny];
		boolean[] leaf = new boolean[dist.length];
		int[] queue = new int[dist.length];
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			int y0 = Math.max(Strip.forestFloor(layer) - LEAF_BAND_BELOW, Strip.layerBottom(layer));
			int y1 = Math.min(Strip.forestFloor(layer) + LEAF_BAND_ABOVE, Strip.layerTop(layer));
			int head = 0, tail = 0;
			boolean anyLeaf = false;
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					ChunkAccess chunk = chunks[((x >> 4) - center.x() + 1) * 3 + (z >> 4) - center.z() + 1];
					for (int y = y0; y <= y1; y++) {
						int i = ((x - minX) * nz + (z - minZ)) * ny + (y - y0);
						BlockState state = chunk.getBlockState(at.set(x, y, z));
						leaf[i] = false;
						dist[i] = LeavesBlock.DECAY_DISTANCE;
						if (state.is(BlockTags.PREVENTS_NEARBY_LEAF_DECAY)) {
							dist[i] = 0;
							queue[tail++] = i;
						} else if (state.getBlock() instanceof LeavesBlock && state.hasProperty(LeavesBlock.DISTANCE)) {
							leaf[i] = true;
							anyLeaf = true;
						}
					}
				}
			}
			if (!anyLeaf) continue;

			int h = y1 - y0 + 1;
			while (head < tail) {
				int i = queue[head++];
				int d = dist[i] + 1;
				if (d >= LeavesBlock.DECAY_DISTANCE) continue;
				int iy = i % ny, ixz = i / ny, iz = ixz % nz, ix = ixz / nz;
				for (int dir = 0; dir < 6; dir++) {
					int j;
					switch (dir) {
						case 0 -> j = iy > 0 ? i - 1 : -1;
						case 1 -> j = iy < h - 1 ? i + 1 : -1;
						case 2 -> j = iz > 0 ? i - ny : -1;
						case 3 -> j = iz < nz - 1 ? i + ny : -1;
						case 4 -> j = ix > 0 ? i - nz * ny : -1;
						default -> j = ix < nx - 1 ? i + nz * ny : -1;
					}
					if (j >= 0 && leaf[j] && dist[j] > d) {
						dist[j] = (byte) d;
						queue[tail++] = j;
					}
				}
			}

			// write the distances back; remove what is cut off
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					int ix = x - minX, iz = z - minZ;
					boolean nearOpen = (openMinX && ix < LeavesBlock.DECAY_DISTANCE - 1) || (openMaxX && nx - 1 - ix < LeavesBlock.DECAY_DISTANCE - 1)
						|| (openMinZ && iz < LeavesBlock.DECAY_DISTANCE - 1) || (openMaxZ && nz - 1 - iz < LeavesBlock.DECAY_DISTANCE - 1);
					ChunkAccess chunk = null;
					for (int y = y0; y <= y1; y++) {
						int i = (ix * nz + iz) * ny + (y - y0);
						if (!leaf[i]) continue;
						if (chunk == null) chunk = chunks[((x >> 4) - center.x() + 1) * 3 + (z >> 4) - center.z() + 1];
						BlockState state = chunk.getBlockState(at.set(x, y, z));
						int old = state.getValue(LeavesBlock.DISTANCE);
						int d = nearOpen ? Math.min(dist[i], old) : dist[i];
						if (d >= LeavesBlock.DECAY_DISTANCE && !nearOpen && !state.getValue(LeavesBlock.PERSISTENT)) {
							chunk.setBlockState(at, state.getFluidState().createLegacyBlock());
						} else if (d != old) {
							chunk.setBlockState(at, state.setValue(LeavesBlock.DISTANCE, d));
						}
					}
				}
			}
		}
	}

	private void setIfInChunk(ChunkAccess chunk, ChunkPos pos, int x, int y, int z, BlockState state) {
		if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ()) return;
		if (!this.bounds.inside(x, z)) return;
		set(chunk, x - pos.getMinBlockX(), y, z - pos.getMinBlockZ(), state);
	}

	// ------------------------------------------------------------------ ores

	private record Ore(BlockState stone, BlockState deepslate, int minLayer, int maxLayer, int veins, int perLayer, int size) {
		int count(int layer) {
			return layer < this.minLayer || layer > this.maxLayer ? 0 : this.veins + this.perLayer * (layer - this.minLayer);
		}
	}

	private static Ore ore(Block stone, Block deepslate, int minLayer, int maxLayer, int veins, int perLayer, int size) {
		return new Ore(stone.defaultBlockState(), deepslate.defaultBlockState(), minLayer, maxLayer, veins, perLayer, size);
	}

	/**
	 * Vein counts are per 16x16 chunk per layer. Each tool tier unlocks one layer deeper (layer 1:
	 * coal + copper, 2: iron, 3: gold, 4: redstone + lapis, 5: diamond, 6: obsidian + emerald,
	 * 7: ancient debris) and keeps appearing below; the earlier an ore unlocks, the more common it is.
	 */
	private static final List<Ore> ORES = List.of(
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

	private static boolean isLayerStone(BlockState state) {
		for (int i = 1; i < BASE_STONE.length; i++) {
			if (state == BASE_STONE[i] || state == PATCH_STONE[i]) return true;
		}
		return false;
	}

	public void placeOres(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int minX = Math.max(pos.getMinBlockX(), this.bounds.minX()), maxX = Math.min(pos.getMaxBlockX(), this.bounds.maxX());
		int minZ = Math.max(pos.getMinBlockZ(), this.bounds.minZ()), maxZ = Math.min(pos.getMaxBlockZ(), this.bounds.maxZ());
		if (minX > maxX || minZ > maxZ) return;
		double areaScale = (maxX - minX + 1) * (maxZ - minZ + 1) / 256.0;
		RandomSource random = new XoroshiroRandomSource(Strip.mix(this.seed, 77), Strip.mix(pos.x(), pos.z() * 31L + 5));

		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			int bottom = Strip.layerBottom(layer) + Strip.HALL_HEIGHT;
			int top = Strip.layerTop(layer);
			for (Ore ore : ORES) {
				double expected = ore.count(layer) * areaScale;
				int veins = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
				for (int v = 0; v < veins; v++) {
					int x = minX + random.nextInt(maxX - minX + 1);
					int z = minZ + random.nextInt(maxZ - minZ + 1);
					int y = bottom + random.nextInt(top - bottom + 1);
					for (int i = 0; i < ore.size(); i++) {
						if (x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= bottom && y <= top) {
							int lx = x - pos.getMinBlockX(), lz = z - pos.getMinBlockZ();
							BlockState current = get(chunk, lx, y, lz);
							if (isLayerStone(current)) {
								set(chunk, lx, y, lz, current.is(Blocks.DEEPSLATE) ? ore.deepslate() : ore.stone());
							}
						}
						switch (random.nextInt(6)) {
							case 0 -> x++;
							case 1 -> x--;
							case 2 -> y++;
							case 3 -> y--;
							case 4 -> z++;
							default -> z--;
						}
					}
				}
			}
		}
	}
}
