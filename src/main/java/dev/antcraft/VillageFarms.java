package dev.antcraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * Every village of a tall strip world has at least one farm (spec v2 3.7). A village without a
 * farm piece gets one added to its start: a vanilla farm template on free, flat, dry ground inside
 * the village or just beyond its north or south end, within the village's reference radius.
 */
public final class VillageFarms {
	private VillageFarms() {}

	/** How far from the village centre (z) a farm may lie: the reference radius of the bigger villages. */
	static final int RADIUS = 100;

	/** Where the farm stands. Surface: the generator's (translated) heights; deep: the flattened forest floor. */
	public interface Ground {
		/** Top ground block in a column, or MIN_VALUE if the spot is unusable (water, ...). */
		int topAt(int x, int z);
	}

	/** Surface ground through the generator's heights (water and ground below sea level rejected). */
	public static Ground surface(StripChunkGenerator generator, LevelHeightAccessor heights, RandomState randomState) {
		int sea = generator.getSeaLevel();
		return (x, z) -> {
			int world = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, randomState);
			int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, randomState);
			if (world != floor || world - 1 < sea) return Integer.MIN_VALUE;
			return world - 1;
		};
	}

	public static Ground deep(int forestFloor) {
		return (x, z) -> forestFloor;
	}

	private static String villageType(String location) {
		int i = location.indexOf("village/");
		if (i < 0) return null;
		String rest = location.substring(i + "village/".length());
		int slash = rest.indexOf('/');
		return slash < 0 ? null : rest.substring(0, slash);
	}

	private static String[] farmTemplates(String type) {
		return switch (type) {
			case "taiga" -> new String[] {"village/taiga/houses/taiga_large_farm_1", "village/taiga/houses/taiga_small_farm_1"};
			case "savanna" -> new String[] {"village/savanna/houses/savanna_large_farm_1", "village/savanna/houses/savanna_small_farm"};
			case "snowy" -> new String[] {"village/snowy/houses/snowy_farm_2", "village/snowy/houses/snowy_farm_1"};
			case "desert" -> new String[] {"village/desert/houses/desert_large_farm_1", "village/desert/houses/desert_farm_2"};
			default -> new String[] {"village/plains/houses/plains_large_farm_1", "village/plains/houses/plains_small_farm_1"};
		};
	}

	public static boolean isVillage(StructureStart start) {
		return start.isValid() && !start.getPieces().isEmpty() && start.getPieces().getFirst() instanceof PoolElementStructurePiece
			&& VaultPlan.location(start.getPieces().getFirst()).contains("village/");
	}

	public static boolean hasFarm(StructureStart start) {
		for (StructurePiece piece : start.getPieces()) if (VaultPlan.location(piece).contains("farm")) return true;
		return false;
	}

	/**
	 * The start with a farm (the same start if it already has one, or no spot was found).
	 */
	public static StructureStart ensure(StructureStart start, RegistryAccess registryAccess, StructureTemplateManager templates, StripBounds bounds, long seed, Ground ground) {
		return ensure(start, registryAccess, templates, bounds, seed, ground, true);
	}

	/** As above; {@code log = false} for candidate tries (the caller logs the chosen one). */
	public static StructureStart ensure(StructureStart start, RegistryAccess registryAccess, StructureTemplateManager templates, StripBounds bounds, long seed, Ground ground,
		boolean log) {
		if (!isVillage(start) || hasFarm(start)) return start;
		String first = VaultPlan.location(start.getPieces().getFirst());
		String type = villageType(first);
		if (type == null) return start;
		boolean zombie = first.contains("zombie");
		String processors = (zombie ? "zombie_" : "farm_") + type;
		Holder<StructureProcessorList> list = registryAccess.lookupOrThrow(Registries.PROCESSOR_LIST)
			.get(ResourceKey.create(Registries.PROCESSOR_LIST, Identifier.withDefaultNamespace(processors))).map(h -> (Holder<StructureProcessorList>) h).orElse(null);
		if (list == null) return start;
		ChunkPos chunkPos = start.getChunkPos();
		RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 9100), Strip.mix(chunkPos.x(), chunkPos.z()));
		BoundingBox village = start.getBoundingBox();
		BoundingBox centre = start.getPieces().getFirst().getBoundingBox();
		int cz = (centre.minZ() + centre.maxZ()) / 2;
		for (String location : farmTemplates(type)) {
			SinglePoolElement element = StructurePoolElement.single("minecraft:" + location, list).apply(StructureTemplatePool.Projection.RIGID);
			PoolElementStructurePiece farm = place(start, element, templates, bounds, village, cz, ground, random);
			if (farm != null) {
				List<StructurePiece> pieces = new ArrayList<>(start.getPieces());
				pieces.add(farm);
				if (log) AntCraft.LOGGER.info("AntCraft: village at {} got a farm ({}) at {}", chunkPos, location, farm.getBoundingBox());
				return new StructureStart(start.getStructure(), chunkPos, start.getReferences(), new PiecesContainer(pieces));
			}
		}
		if (log) AntCraft.LOGGER.warn("AntCraft: no spot for a farm in the village at {} ({})", chunkPos, village);
		return start;
	}

	private static @Nullable PoolElementStructurePiece place(
		StructureStart start, SinglePoolElement element, StructureTemplateManager templates, StripBounds bounds, BoundingBox village, int cz, Ground ground, RandomSource random
	) {
		var size = element.getSize(templates, Rotation.NONE);
		int longSide = Math.max(size.getX(), size.getZ());
		// the long side along the strip when the strip is narrow
		boolean xLong = size.getX() >= size.getZ();
		Rotation rotation;
		if (bounds.width() < 2 * longSide) rotation = xLong ? (random.nextBoolean() ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90) : (random.nextBoolean() ? Rotation.NONE : Rotation.CLOCKWISE_180);
		else rotation = Rotation.getRandom(random);
		var rotated = element.getSize(templates, rotation);
		int sx = rotated.getX(), sz = rotated.getZ();
		int minX = bounds.minX() + 1, maxX = bounds.maxX() - 1 - (sx - 1);
		if (maxX < minX) return null;
		int xOffset = random.nextInt(maxX - minX + 1);
		List<int[]> candidates = new ArrayList<>();
		// (a) gaps inside the village, (b) just beyond its north or south end
		for (int z = village.minZ(); z + sz - 1 <= village.maxZ(); z += 2) candidates.add(new int[] {z, 0});
		boolean southFirst = random.nextBoolean();
		for (int d = 2; d <= 12; d++) {
			int north = village.minZ() - d - (sz - 1), south = village.maxZ() + d;
			candidates.add(new int[] {southFirst ? south : north, 1});
			candidates.add(new int[] {southFirst ? north : south, 1});
		}
		for (int[] c : candidates) {
			int z = c[0];
			if (z < bounds.minZ() + 1 || z + sz - 1 > bounds.maxZ() - 1) continue;
			if (z < cz - RADIUS || z + sz - 1 > cz + RADIUS) continue;
			for (int i = 0; i <= maxX - minX; i++) {
				int x = minX + (xOffset + i) % (maxX - minX + 1);
				BoundingBox box = new BoundingBox(x, 0, z, x + sx - 1, 0, z + sz - 1);
				if (touchesPiece(start, box)) continue;
				int y = groundY(ground, box);
				if (y == Integer.MIN_VALUE) continue;
				BlockPos origin = rotatedOrigin(element, templates, rotation, x, y, z);
				BoundingBox real = element.getBoundingBox(templates, origin, rotation);
				if (real.minX() < bounds.minX() + 1 || real.maxX() > bounds.maxX() - 1 || real.minZ() < bounds.minZ() + 1 || real.maxZ() > bounds.maxZ() - 1) continue;
				if (touchesPiece(start, real)) continue;
				return new PoolElementStructurePiece(templates, element, origin, 1, rotation, real, LiquidSettings.APPLY_WATERLOGGING);
			}
		}
		return null;
	}

	/** Template origin that puts the rotated template's box corner at (x, y, z). */
	private static BlockPos rotatedOrigin(SinglePoolElement element, StructureTemplateManager templates, Rotation rotation, int x, int y, int z) {
		BoundingBox at0 = element.getBoundingBox(templates, BlockPos.ZERO, rotation);
		return new BlockPos(x - at0.minX(), y, z - at0.minZ());
	}

	/** True if the box, inflated by 1, touches a piece of the village (horizontally; pieces are all at ground level). */
	private static boolean touchesPiece(StructureStart start, BoundingBox box) {
		for (StructurePiece piece : start.getPieces()) {
			BoundingBox b = piece.getBoundingBox();
			if (box.minX() - 1 <= b.maxX() && box.maxX() + 1 >= b.minX() && box.minZ() - 1 <= b.maxZ() && box.maxZ() + 1 >= b.minZ()) return true;
		}
		return false;
	}

	/** The farm's ground y: the lowest of its corners and centre if they differ by at most 3, else MIN_VALUE. */
	private static int groundY(Ground ground, BoundingBox box) {
		int[][] points = {{box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()},
			{(box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2}};
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int[] p : points) {
			int y = ground.topAt(p[0], p[1]);
			if (y == Integer.MIN_VALUE) return Integer.MIN_VALUE;
			lo = Math.min(lo, y);
			hi = Math.max(hi, y);
		}
		return hi - lo <= 3 ? lo : Integer.MIN_VALUE;
	}
}
