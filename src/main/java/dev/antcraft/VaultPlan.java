package dev.antcraft;

import dev.antcraft.mixin.StructureTemplateAccessor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * The Trial Vault's way in and way out (spec v2 3.5.4): a pure function of the vault start's piece
 * boxes, element names and rotations plus a fixed table of the two start templates, so terrain
 * generation (holes and landing rooms), decoration (the scaffolding wells) and runtime (hints,
 * self check) all agree.
 */
public final class VaultPlan {
	public static final ResourceKey<Structure> KEY = ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "deep_trial_vault"));

	/** Pieces that are chamber rooms (the exit lies in the deepest one). */
	private static final Pattern CHAMBER_ROOM = Pattern.compile("trial_chambers/chamber/(chamber_\\d+|assembly|eruption|slanted|pedestal)$");
	private static final List<String> START_TEMPLATES = List.of("minecraft:trial_chambers/corridor/end_1", "minecraft:trial_chambers/corridor/end_2");

	/** A start-template column the entrance well can use: offset from the piece position, and the floor under its air. */
	record WellColumn(int dx, int dz, int floorDy) {}

	/** template location -> rotation -> well columns (fixed data from the jar, loaded at server start). */
	private static volatile @Nullable Map<String, Map<Rotation, List<WellColumn>>> table;

	/** Loads the start-template table (SERVER_STARTING). */
	public static void loadTable(StructureTemplateManager templates) {
		Map<String, Map<Rotation, List<WellColumn>>> result = new HashMap<>();
		for (String location : START_TEMPLATES) {
			StructureTemplate template = templates.getOrCreate(Identifier.parse(location));
			Map<Rotation, List<WellColumn>> byRotation = new EnumMap<>(Rotation.class);
			for (Rotation rotation : Rotation.values()) byRotation.put(rotation, wellColumns(template, rotation));
			result.put(location, byRotation);
		}
		table = result;
		AntCraft.LOGGER.info("AntCraft: trial vault start table loaded ({} templates, {} columns unrotated)", result.size(),
			result.values().stream().mapToInt(m -> m.get(Rotation.NONE).size()).sum());
	}

	/**
	 * Template columns with a run of at least 3 air blocks over a sturdy floor, and nothing above
	 * that air but plain blocks (no block entity, jigsaw or structure void) up to the template top.
	 */
	static List<WellColumn> wellColumns(StructureTemplate template, Rotation rotation) {
		List<WellColumn> list = new ArrayList<>();
		List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).antcraft$palettes();
		if (palettes.isEmpty()) return list;
		var size = template.getSize();
		int sx = size.getX(), sy = size.getY(), sz = size.getZ();
		BlockState[] states = new BlockState[sx * sy * sz];
		boolean[] special = new boolean[states.length];
		for (StructureTemplate.StructureBlockInfo info : palettes.getFirst().blocks()) {
			BlockPos p = info.pos();
			if (p.getX() < 0 || p.getY() < 0 || p.getZ() < 0 || p.getX() >= sx || p.getY() >= sy || p.getZ() >= sz) continue;
			int i = (p.getX() * sy + p.getY()) * sz + p.getZ();
			states[i] = info.state();
			special[i] = info.nbt() != null || info.state().is(Blocks.JIGSAW) || info.state().hasBlockEntity();
		}
		for (int x = 0; x < sx; x++) {
			for (int z = 0; z < sz; z++) {
				int run = 0;
				for (int y = sy - 1; y >= 0; y--) {
					int i = (x * sy + y) * sz + z;
					BlockState state = states[i];
					if (state == null || special[i]) break; // structure void, a block entity or a jigsaw
					if (state.isAir()) {
						run++;
						continue;
					}
					if (run >= 3) {
						if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) {
							BlockPos rotated = StructureTemplate.transform(new BlockPos(x, y, z), Mirror.NONE, rotation, BlockPos.ZERO);
							list.add(new WellColumn(rotated.getX(), rotated.getZ(), y));
						}
						break;
					}
					run = 0;
				}
			}
		}
		return list;
	}

	// ------------------------------------------------------------------ the plan

	public final BoundingBox startBox;
	/** Entrance column (the floor-6 hole) and the floor under the start piece's air there; valid = false if none. */
	public final int nx, nz, entranceFloorY;
	static final int NO_FLOOR = Integer.MIN_VALUE;
	public final boolean entranceValid;
	/** The deepest chamber room and the exit hole (floor 7) inside it. */
	public final BoundingBox exitRoom;
	public final int ex, ez;
	/** No chamber room had room for the exit hole: a hallway or the start piece holds it. */
	public final boolean fallback;
	public final int chamberRooms;
	/** Every piece box (final coordinates). */
	public final List<BoundingBox> pieces;

	private VaultPlan(BoundingBox startBox, int nx, int nz, int entranceFloorY, boolean entranceValid, BoundingBox exitRoom, int ex, int ez, boolean fallback,
		int chamberRooms, List<BoundingBox> pieces) {
		this.startBox = startBox;
		this.nx = nx;
		this.nz = nz;
		this.entranceFloorY = entranceFloorY;
		this.entranceValid = entranceValid;
		this.exitRoom = exitRoom;
		this.ex = ex;
		this.ez = ez;
		this.fallback = fallback;
		this.chamberRooms = chamberRooms;
		this.pieces = pieces;
	}

	static String location(StructurePiece piece) {
		if (!(piece instanceof PoolElementStructurePiece pool)) return "";
		StructurePoolElement element = pool.getElement();
		if (element instanceof SinglePoolElement single) {
			try {
				return single.getTemplateLocation().toString();
			} catch (RuntimeException e) {
				return element.toString();
			}
		}
		return element.toString();
	}

	static boolean isChamberRoom(StructurePiece piece) {
		return CHAMBER_ROOM.matcher(location(piece)).find();
	}

	/** The plan of a (shifted) vault start, or null if it is not a valid vault start. */
	public static @Nullable VaultPlan of(StructureStart start, StripBounds bounds) {
		if (!start.isValid() || start.getPieces().isEmpty() || !(start.getPieces().getFirst() instanceof PoolElementStructurePiece first)) return null;
		BoundingBox startBox = first.getBoundingBox();
		List<BoundingBox> boxes = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) boxes.add(piece.getBoundingBox());

		// entrance: the start template's well column nearest the start box centre
		int nx = 0, nz = 0, floorY = NO_FLOOR;
		boolean entrance = false;
		Map<String, Map<Rotation, List<WellColumn>>> t = table;
		List<WellColumn> wells = t == null ? null : t.getOrDefault(location(first), Map.of()).get(first.getRotation());
		if (wells != null) {
			double cx = (startBox.minX() + startBox.maxX()) / 2.0, cz = (startBox.minZ() + startBox.maxZ()) / 2.0;
			double best = Double.MAX_VALUE;
			BlockPos origin = first.getPosition();
			for (WellColumn w : wells) {
				int x = origin.getX() + w.dx(), z = origin.getZ() + w.dz();
				if (x < startBox.minX() + 3 || x > startBox.maxX() - 3 || z < startBox.minZ() + 3 || z > startBox.maxZ() - 3) continue;
				if (x < bounds.minX() + 2 || x > bounds.maxX() - 2 || z < bounds.minZ() + 2 || z > bounds.maxZ() - 2) continue;
				double d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
				if (d < best || d == best && (x < nx || x == nx && z < nz)) {
					best = d;
					nx = x;
					nz = z;
					floorY = origin.getY() + w.floorDy();
					entrance = true;
				}
			}
		}

		if (!entrance) {
			// no table column (should not happen with the 350-column table): the start box centre, the well finds its floor
			nx = Math.clamp(startBox.getCenter().getX(), Math.max(startBox.minX() + 3, bounds.minX() + 2), Math.min(startBox.maxX() - 3, bounds.maxX() - 2));
			nz = Math.clamp(startBox.getCenter().getZ(), Math.max(startBox.minZ() + 3, bounds.minZ() + 2), Math.min(startBox.maxZ() - 3, bounds.maxZ() - 2));
		}

		// exit: the deepest chamber room (ties: nearest the strip's centre z) with room for the hole
		List<StructurePiece> rooms = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) if (isChamberRoom(piece)) rooms.add(piece);
		rooms.sort((a, b) -> {
			int c = Integer.compare(a.getBoundingBox().minY(), b.getBoundingBox().minY());
			if (c != 0) return c;
			return Integer.compare(Math.abs(a.getBoundingBox().getCenter().getZ() - bounds.centerZ()), Math.abs(b.getBoundingBox().getCenter().getZ() - bounds.centerZ()));
		});
		for (StructurePiece room : rooms) {
			int[] hole = holeIn(room.getBoundingBox(), bounds, 3);
			if (hole != null) return new VaultPlan(startBox, nx, nz, floorY, entrance, room.getBoundingBox(), hole[0], hole[1], false, rooms.size(), boxes);
		}
		// fallback: the deepest piece with at least a 5x5 floor inside the strip, else the start piece
		List<StructurePiece> others = new ArrayList<>(start.getPieces());
		others.sort((a, b) -> Integer.compare(a.getBoundingBox().minY(), b.getBoundingBox().minY()));
		for (StructurePiece piece : others) {
			int[] hole = holeIn(piece.getBoundingBox(), bounds, 2);
			if (hole != null) return new VaultPlan(startBox, nx, nz, floorY, entrance, piece.getBoundingBox(), hole[0], hole[1], true, rooms.size(), boxes);
		}
		int[] hole = {Math.clamp(startBox.getCenter().getX(), bounds.minX() + 3, bounds.maxX() - 3), Math.clamp(startBox.getCenter().getZ(), bounds.minZ() + 3, bounds.maxZ() - 3)};
		return new VaultPlan(startBox, nx, nz, floorY, entrance, startBox, hole[0], hole[1], true, rooms.size(), boxes);
	}

	/**
	 * The column of a box nearest its centre that is at least {@code margin} from its edges and 3
	 * from the strip walls, preferring columns one or more blocks inside their chunk: the exit well
	 * and its nook sit next to the hole and are cut while only the hole's chunk is decorated.
	 */
	private static int @Nullable [] holeIn(BoundingBox box, StripBounds bounds, int margin) {
		int minX = Math.max(box.minX() + margin, bounds.minX() + 3), maxX = Math.min(box.maxX() - margin, bounds.maxX() - 3);
		int minZ = Math.max(box.minZ() + margin, bounds.minZ() + 3), maxZ = Math.min(box.maxZ() - margin, bounds.maxZ() - 3);
		if (minX > maxX || minZ > maxZ) return null;
		int cx = (box.minX() + box.maxX()) >> 1, cz = (box.minZ() + box.maxZ()) >> 1;
		return new int[] {inChunkNearest(cx, minX, maxX), inChunkNearest(cz, minZ, maxZ)};
	}

	/** The value of [min, max] nearest {@code c} whose chunk-local coordinate is 1..14 (ties: lower), or the plain clamp if none. */
	static int inChunkNearest(int c, int min, int max) {
		int best = Integer.MIN_VALUE;
		for (int v = min; v <= max; v++) {
			int local = v & 15;
			if (local < 1 || local > 14) continue;
			if (best == Integer.MIN_VALUE || Math.abs(v - c) < Math.abs(best - c)) best = v;
		}
		return best != Integer.MIN_VALUE ? best : Math.clamp(c, min, max);
	}

	/** Hole of floor k if it is one of the vault's two floors (k = vaultLayer - 1 or vaultLayer). */
	public boolean ownsFloor(StripLayout layout, int k) {
		return layout.vaultLayer >= 1 && (k == layout.vaultLayer - 1 || k == layout.vaultLayer);
	}

	public int holeX(StripLayout layout, int k) {
		return k == layout.vaultLayer - 1 ? this.nx : this.ex;
	}

	public int holeZ(StripLayout layout, int k) {
		return k == layout.vaultLayer - 1 ? this.nz : this.ez;
	}

	// ------------------------------------------------------------------ best-of-8 scoring

	/** How good a candidate start is: 2 = meets every target, 1 = has an entrance, 0 = unusable. */
	static int grade(@Nullable VaultPlan plan) {
		if (plan == null || !plan.entranceValid) return 0;
		return plan.chamberRooms >= 6 && plan.deepRoom() ? 2 : 1;
	}

	/** The exit room lies at least 8 below the start piece (the exit well stays short). */
	boolean deepRoom() {
		return !this.fallback && this.exitRoom.minY() <= this.startBox.minY() - 8;
	}

	/** Pieces reach above and below the start piece (the vault grows both ways). */
	boolean growsBothWays() {
		return this.maxY() > this.startBox.maxY() && this.minY() < this.startBox.minY();
	}

	public int minY() {
		int y = Integer.MAX_VALUE;
		for (BoundingBox box : this.pieces) y = Math.min(y, box.minY());
		return y;
	}

	public int maxY() {
		int y = Integer.MIN_VALUE;
		for (BoundingBox box : this.pieces) y = Math.max(y, box.maxY());
		return y;
	}

	/**
	 * Best-of-N ranking: the grade first, then the most chamber rooms, then growth both ways, then
	 * a deep exit room, then the most pieces.
	 */
	static long score(VaultPlan plan) {
		return (long) grade(plan) << 40 | (long) Math.min(plan.chamberRooms, 255) << 24 | (plan.growsBothWays() ? 1L : 0L) << 23 | (plan.deepRoom() ? 1L : 0L) << 22
			| Math.min(plan.pieces.size(), (1 << 22) - 1);
	}

	// ------------------------------------------------------------------ lookups

	/** The vault start referenced by a chunk (terrain or decoration time), or null. */
	public static @Nullable VaultPlan find(StructureManager structureManager, ChunkPos pos, StripBounds bounds) {
		Structure structure = structureManager.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(KEY);
		if (structure == null) return null;
		for (StructureStart start : structureManager.startsForStructure(pos.x(), pos.z(), s -> s == structure)) {
			VaultPlan plan = of(start, bounds);
			if (plan != null) return plan;
		}
		return null;
	}

	private static final Map<String, VaultPlan> RUNTIME = new ConcurrentHashMap<>();

	/** Runtime plan of a strip overworld (reads the start chunk's structure starts; cached per level). */
	public static @Nullable VaultPlan get(ServerLevel level) {
		StripLayout layout = Strip.layout(level);
		StripBounds bounds = Strip.bounds(level);
		if (layout == null || bounds == null || layout.vaultLayer < 1 || !(level.getChunkSource().getGenerator() instanceof StripChunkGenerator generator)) return null;
		String key = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT) + "|" + level.getSeed();
		VaultPlan cached = RUNTIME.get(key);
		if (cached != null) return cached;
		Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(KEY);
		if (structure == null) return null;
		ChunkPos chunk = generator.vaultChunk(level.getSeed());
		ChunkAccess access = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.STRUCTURE_STARTS);
		StructureStart start = access.getStartForStructure(structure);
		VaultPlan plan = start == null ? null : of(start, bounds);
		if (plan != null) RUNTIME.put(key, plan);
		return plan;
	}

	public static void clearRuntimeCache() {
		RUNTIME.clear();
	}

	/** Hole of floor k at runtime: the vault's for its two floors, else the seeded one. */
	public static int holeX(ServerLevel level, StripLayout layout, StripBounds bounds, int k) {
		if (layout.vaultLayer >= 1 && (k == layout.vaultLayer - 1 || k == layout.vaultLayer)) {
			VaultPlan plan = get(level);
			if (plan != null) return plan.holeX(layout, k);
		}
		return layout.holeX(level.getSeed(), k, bounds);
	}

	public static int holeZ(ServerLevel level, StripLayout layout, StripBounds bounds, int k) {
		if (layout.vaultLayer >= 1 && (k == layout.vaultLayer - 1 || k == layout.vaultLayer)) {
			VaultPlan plan = get(level);
			if (plan != null) return plan.holeZ(layout, k);
		}
		return layout.holeZ(level.getSeed(), k, bounds);
	}

	// ------------------------------------------------------------------ the wells (decoration)

	private static final BlockState SCAFFOLD = Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, false);

	/**
	 * Cuts the scaffolding wells whose column lies in this chunk, after the chunk's structure
	 * pieces were placed: the entrance well from the floor-6 landing room down into the start
	 * piece, and the exit well from the deepest room's floor down to the vault bottom with the nook
	 * to the floor-7 hole.
	 */
	public void cutWells(WorldGenLevel level, ChunkPos pos, StripLayout layout, StripBounds bounds) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int landingFloor = layout.floorBase(layout.vaultLayer - 1) - 4;
		if (inChunk(pos, this.nx, this.nz)) {
			int floor = this.entranceValid ? this.entranceFloorY : this.scanEntranceFloor(level, at, landingFloor);
			for (int y = floor + 1; y <= landingFloor; y++) level.setBlock(at.set(this.nx, y, this.nz), SCAFFOLD, Block.UPDATE_CLIENTS);
		}
		if (!inChunk(pos, this.ex, this.ez)) return;
		int bottom = layout.layerBottom(layout.vaultLayer);
		int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		int[] chosen = null;
		int floorY = 0;
		// passes: a sturdy room floor over a column free of other pieces; a sturdy floor anyway; any spot
		// with two free blocks over it and no block entity down the column; then any neighbour at all
		for (int pass = 0; pass < 4 && chosen == null; pass++) {
			for (int[] o : offsets) {
				int x = this.ex + o[0], z = this.ez + o[1];
				// the column and the nook stay inside this chunk: its pieces are placed, its neighbours' maybe not
				// (the plan keeps the hole one block inside its chunk, so this only bites in odd strips)
				if (pass < 3 && (!inChunk(pos, x, z) || !inChunk(pos, this.ex - o[0], this.ez - o[1]))) continue;
				if (!bounds.inside(x, z)) continue;
				int f = pass < 2 ? this.roomFloor(level, at, x, z) : this.looseFloor(level, at, x, z);
				if (f == Integer.MIN_VALUE) continue;
				if (pass == 0 && this.crossesOtherPiece(x, z, bottom, f)) continue;
				if (pass == 2 && hasBlockEntity(level, at, x, z, bottom, f)) continue;
				chosen = new int[] {x, z, o[0], o[1]};
				floorY = f;
				break;
			}
		}
		if (chosen == null) {
			// cannot happen: the hole is 3 from the walls, so the last pass takes any neighbour
			AntCraft.LOGGER.error("AntCraft: no exit well column next to the trial vault exit at ({} {}) in room {}", this.ex, this.ez, this.exitRoom);
			return;
		}
		for (int y = bottom; y <= floorY; y++) level.setBlock(at.set(chosen[0], y, chosen[1]), SCAFFOLD, Block.UPDATE_CLIENTS);
		// the nook: the hole column and one more cell beyond it, two high, lit so nothing spawns
		for (int y = bottom; y <= bottom + 1; y++) {
			level.setBlock(at.set(this.ex, y, this.ez), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			int bx = this.ex - chosen[2], bz = this.ez - chosen[3];
			if (bounds.inside(bx, bz)) level.setBlock(at.set(bx, y, bz), y == bottom + 1 ? DeepTerrain.LIGHT_15 : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		AntCraft.LOGGER.info("AntCraft: trial vault exit well at ({} {}), floor {}, hole ({} {})", chosen[0], chosen[1], floorY, this.ex, this.ez);
	}

	/** The exit room's lowest floor in a column: a sturdy block with two free blocks over it and no block entity, or MIN_VALUE. */
	private int roomFloor(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z) {
		for (int y = this.exitRoom.minY(); y < this.exitRoom.maxY() - 1; y++) {
			BlockState state = level.getBlockState(at.set(x, y, z));
			if (state.hasBlockEntity() || !state.isFaceSturdy(level, at, Direction.UP)) continue;
			if (level.getBlockState(at.set(x, y + 1, z)).isAir() && level.getBlockState(at.set(x, y + 2, z)).isAir()) return y;
		}
		return Integer.MIN_VALUE;
	}

	/** Fallback floor: the lowest block of the exit room with two free blocks over it, or the room's floor row. */
	private int looseFloor(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z) {
		for (int y = this.exitRoom.minY(); y < this.exitRoom.maxY() - 1; y++) {
			if (level.getBlockState(at.set(x, y, z)).isAir()) continue;
			if (level.getBlockState(at.set(x, y + 1, z)).isAir() && level.getBlockState(at.set(x, y + 2, z)).isAir()) return y;
		}
		return this.exitRoom.minY() + 1;
	}

	private static boolean hasBlockEntity(WorldGenLevel level, BlockPos.MutableBlockPos at, int x, int z, int bottom, int top) {
		for (int y = bottom; y <= top; y++) if (level.getBlockState(at.set(x, y, z)).hasBlockEntity()) return true;
		return false;
	}

	/** Entrance floor when the table had no column: the first sturdy block under free space inside the start piece. */
	private int scanEntranceFloor(WorldGenLevel level, BlockPos.MutableBlockPos at, int landingFloor) {
		for (int y = Math.min(landingFloor, this.startBox.maxY() - 1); y > this.startBox.minY(); y--) {
			if (!level.getBlockState(at.set(this.nx, y, this.nz)).isAir()) continue;
			BlockState below = level.getBlockState(at.set(this.nx, y - 1, this.nz));
			if (below.isFaceSturdy(level, at, Direction.UP)) return y - 1;
		}
		return this.startBox.minY();
	}

	private boolean crossesOtherPiece(int x, int z, int bottom, int floorY) {
		for (BoundingBox box : this.pieces) {
			if (box.equals(this.exitRoom)) continue;
			if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ() && box.minY() < floorY && box.maxY() >= bottom) return true;
		}
		return false;
	}

	private static boolean inChunk(ChunkPos pos, int x, int z) {
		return x >= pos.getMinBlockX() && x <= pos.getMaxBlockX() && z >= pos.getMinBlockZ() && z <= pos.getMaxBlockZ();
	}

	@Override
	public String toString() {
		return "VaultPlan[start " + this.startBox + ", entrance " + (this.entranceValid ? "(" + this.nx + " " + this.nz + ") floor " + this.entranceFloorY : "FALLBACK (" + this.nx + " " + this.nz + ")")
			+ ", exit (" + this.ex + " " + this.ez + ") in " + this.exitRoom + (this.fallback ? " (fallback)" : "") + ", " + this.chamberRooms + " chamber rooms, "
			+ this.pieces.size() + " pieces]";
	}
}
