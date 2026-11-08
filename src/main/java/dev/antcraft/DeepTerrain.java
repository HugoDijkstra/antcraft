package dev.antcraft;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
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
 * All of it is a pure function of (seed, x, y, z), so chunks regenerate identically.
 */
public final class DeepTerrain {
	private static final Map<Long, DeepTerrain> BY_SEED = new ConcurrentHashMap<>();

	public static DeepTerrain forSeed(long seed) {
		return BY_SEED.computeIfAbsent(seed, DeepTerrain::new);
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

	private DeepTerrain(long seed) {
		this.seed = seed;
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

	// ------------------------------------------------------------------ chunk writes

	static void set(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
		section.setBlockState(lx, y & 15, lz, state, false);
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

	private boolean isPillar(int layer, int x, int z) {
		for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
			// never block the way to a hole
			if (Strip.floorTop(k) + 1 == Strip.hallBottom(layer)
				&& Math.abs(x - Strip.holeX(this.seed, k)) <= 2 && Math.abs(z - Strip.holeZ(this.seed, k)) <= 2) {
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
		if (glowValue > hangThreshold) {
			int length = 1 + (int) ((glowValue - hangThreshold) * 16);
			for (int i = 1; i <= length && ceil - i > surface + 6; i++) {
				set(chunk, lx, ceil - i, lz, layer == 8 ? SHROOMLIGHT : GLOWSTONE);
			}
		}
		this.decorateForestColumn(chunk, lx, lz, x, z, layer, surface, ceil, pond, ground);
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
	private static boolean waterNextTo(WorldGenLevel level, int x, int z, int surface) {
		int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] o : offsets) {
			int nx = x + o[0], nz = z + o[1];
			if (Strip.inside(nx, nz) && level.getFluidState(new BlockPos(nx, surface, nz)).is(net.minecraft.tags.FluidTags.WATER)) return true;
		}
		return false;
	}

	/**
	 * Ground plants and the invisible light grid of the forests. Runs during decoration, after the
	 * trees: lights and petals/carpets are not tree-replaceable and would stop trees from growing.
	 */
	public void decorateForests(WorldGenLevel level, ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int x = Math.max(pos.getMinBlockX(), Strip.MIN_X); x <= Math.min(pos.getMaxBlockX(), Strip.MAX_X); x++) {
			for (int z = Math.max(pos.getMinBlockZ(), Strip.MIN_Z); z <= Math.min(pos.getMaxBlockZ(), Strip.MAX_Z); z++) {
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
					if (Math.floorMod(x, 5) == 2 && Math.floorMod(z, 5) == 2 && level.getBlockState(at.set(x, surface + 3, z)).isAir()) {
						level.setBlock(at, LIGHT_15, Block.UPDATE_CLIENTS);
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
			int hx = Strip.holeX(this.seed, k);
			int hz = Strip.holeZ(this.seed, k);
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
			// floor 4's hole is sealed until the player brings the Nether Key
			if (k == Strip.SEALED_FLOOR) {
				setIfInChunk(chunk, pos, hx, floorTop, hz, ModContent.NETHER_SEAL.defaultBlockState());
			}
			// keep the hall right above the hole open
			for (int y = floorTop + 1; y <= floorTop + 3; y++) {
				setIfInChunk(chunk, pos, hx, y, hz, AIR);
			}
		}
	}

	/**
	 * Fluids that decoration put directly above the floor-0 hall ceiling (lush cave pools, lava).
	 * Covers the whole 3x3 region decoration may write to, so whichever chunk decorates last cleans
	 * up after its neighbours too.
	 */
	public static void sealAboveFirstHall(WorldGenLevel level, ChunkPos center) {
		int from = Strip.hallBottom(0) + Strip.HALL_HEIGHT;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		// y -55..-53 always solid; y -52..-51 only if it is fluid that could flow into the band
		for (int cx = center.x() - 1; cx <= center.x() + 1; cx++) {
			for (int cz = center.z() - 1; cz <= center.z() + 1; cz++) {
				ChunkPos pos = new ChunkPos(cx, cz);
				if (!Strip.chunkOverlapsStrip(pos)) continue;
				ChunkAccess chunk = level.getChunk(cx, cz);
				for (int x = Math.max(pos.getMinBlockX(), Strip.MIN_X); x <= Math.min(pos.getMaxBlockX(), Strip.MAX_X); x++) {
					for (int z = Math.max(pos.getMinBlockZ(), Strip.MIN_Z); z <= Math.min(pos.getMaxBlockZ(), Strip.MAX_Z); z++) {
						for (int y = from; y <= from + 4; y++) {
							if (!chunk.getBlockState(at.set(x, y, z)).getFluidState().isEmpty()) chunk.setBlockState(at, DEEPSLATE);
						}
					}
				}
			}
		}
	}

	/**
	 * Leaves cut off from their logs (by the walls, clipping or a village piece) would decay and drop
	 * sticks and saplings on the first random tick. Make them persistent instead.
	 */
	public static void keepLeaves(WorldGenLevel level, ChunkPos center) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int cx = center.x() - 1; cx <= center.x() + 1; cx++) {
			for (int cz = center.z() - 1; cz <= center.z() + 1; cz++) {
				ChunkPos pos = new ChunkPos(cx, cz);
				if (!Strip.chunkOverlapsStrip(pos)) continue;
				ChunkAccess chunk = level.getChunk(cx, cz);
				for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
					int floor = Strip.forestFloor(layer);
					for (int x = Math.max(pos.getMinBlockX(), Strip.MIN_X); x <= Math.min(pos.getMaxBlockX(), Strip.MAX_X); x++) {
						for (int z = Math.max(pos.getMinBlockZ(), Strip.MIN_Z); z <= Math.min(pos.getMaxBlockZ(), Strip.MAX_Z); z++) {
							// leaves near a wall may have lost their logs when the outside was cut away
							boolean nearWall = x <= Strip.MIN_X + 5 || x >= Strip.MAX_X - 5 || z <= Strip.MIN_Z + 5 || z >= Strip.MAX_Z - 5;
							for (int y = floor - 4; y <= floor + Strip.FOREST_HEIGHT + 4; y++) {
								BlockState state = chunk.getBlockState(at.set(x, y, z));
								if (!state.hasProperty(LeavesBlock.DISTANCE) || !state.hasProperty(LeavesBlock.PERSISTENT) || state.getValue(LeavesBlock.PERSISTENT)) continue;
								if (nearWall || state.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE) {
									chunk.setBlockState(at, state.setValue(LeavesBlock.PERSISTENT, true));
								}
							}
						}
					}
				}
			}
		}
	}

	private static void setIfInChunk(ChunkAccess chunk, ChunkPos pos, int x, int y, int z, BlockState state) {
		if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ()) return;
		if (!Strip.inside(x, z)) return;
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

	/** Vein counts are per 16x16 chunk per layer; ores get richer the deeper you go. */
	private static final List<Ore> ORES = List.of(
		ore(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 1, 4, 14, -3, 12),
		ore(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 1, 8, 12, 1, 9),
		ore(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 1, 5, 8, 0, 10),
		ore(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 1, 8, 4, 1, 8),
		ore(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 1, 8, 6, 1, 8),
		ore(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 1, 8, 3, 0, 7),
		ore(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 1, 8, 2, 1, 6),
		ore(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 4, 8, 2, 0, 3),
		ore(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 6, 8, 1, 1, 2),
		ore(Blocks.OBSIDIAN, Blocks.OBSIDIAN, 3, 8, 1, 0, 6)
	);

	private static boolean isLayerStone(BlockState state) {
		for (int i = 1; i < BASE_STONE.length; i++) {
			if (state == BASE_STONE[i] || state == PATCH_STONE[i]) return true;
		}
		return false;
	}

	public void placeOres(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int minX = Math.max(pos.getMinBlockX(), Strip.MIN_X), maxX = Math.min(pos.getMaxBlockX(), Strip.MAX_X);
		int minZ = Math.max(pos.getMinBlockZ(), Strip.MIN_Z), maxZ = Math.min(pos.getMaxBlockZ(), Strip.MAX_Z);
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
