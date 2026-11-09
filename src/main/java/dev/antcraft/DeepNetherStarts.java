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
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilPieces;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The tall Nether's hand-placed deep starts (spec v2 4.2): nether ruined portals (one in N1, six
 * in N16) and nether fossils (N4). Each has its own {@code antcraft:deep_nether_*} key and start
 * chunk; its pieces sit on a cavern floor found from {@link DeepNetherTerrain#carved} (a pure
 * function), so the result does not depend on chunk order.
 */
public final class DeepNetherStarts {
	private DeepNetherStarts() {}

	public enum Kind { PORTAL, FOSSIL }

	public record Planned(ResourceKey<Structure> key, Kind kind, int layer, int index, ChunkPos chunk, long seed, DeepNetherTerrain terrain) {}

	public static final int FOSSILS = 4, GRAVEYARD_PORTALS = 6;

	public static ResourceKey<Structure> key(String path) {
		return ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, path));
	}

	/** All planned starts: pure function of seed and bounds. */
	public static List<Planned> plan(long seed, StripBounds bounds, NetherLayout layout, DeepNetherTerrain terrain) {
		List<Planned> list = new ArrayList<>();
		if (!layout.tall()) return list;
		int cx = bounds.centerX() >> 4;
		list.add(new Planned(key("deep_nether_ruined_portal_0"), Kind.PORTAL, 1, 0, new ChunkPos(cx, row(seed, bounds, 17000, 0, 1)), seed, terrain));
		for (int i = 0; i < GRAVEYARD_PORTALS; i++) {
			list.add(new Planned(key("deep_nether_ruined_portal_" + (i + 1)), Kind.PORTAL, 16, i + 1, new ChunkPos(cx, row(seed, bounds, 17100, i, GRAVEYARD_PORTALS)), seed, terrain));
		}
		for (int i = 0; i < FOSSILS; i++) {
			list.add(new Planned(key("deep_nether_fossil_" + i), Kind.FOSSIL, 4, i, new ChunkPos(cx, row(seed, bounds, 17200, i, FOSSILS)), seed, terrain));
		}
		return list;
	}

	/** Chunk row of the i-th of n starts of a layer: spread along the strip, seeded within each stretch. */
	private static int row(long seed, StripBounds bounds, int salt, int i, int n) {
		int first = (bounds.minZ() >> 4) + 1, last = (bounds.maxZ() >> 4) - 1;
		int rows = Math.max(1, last - first + 1);
		int stretch = Math.max(1, rows / n);
		int start = first + Math.min(rows - 1, i * rows / n);
		return Math.min(last, start + (int) Math.floorMod(Strip.mix(seed, salt + i), (long) stretch));
	}

	public static void start(Planned planned, RegistryAccess registryAccess, StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templates) {
		Holder<Structure> holder = registryAccess.lookupOrThrow(Registries.STRUCTURE).get(planned.key()).orElse(null);
		if (holder == null) {
			AntCraft.LOGGER.error("AntCraft: structure {} is missing", planned.key().identifier());
			return;
		}
		List<StructurePiece> pieces = build(planned, registryAccess, templates);
		if (pieces.isEmpty()) {
			AntCraft.LOGGER.warn("AntCraft: no cavern floor for {} in chunk {}", planned.key().identifier(), planned.chunk());
			return;
		}
		Structure structure = holder.value();
		StructureStart previous = structureManager.getStartForStructure(structure, chunk);
		StructureStart start = new StructureStart(structure, planned.chunk(), previous != null ? previous.getReferences() : 0, new PiecesContainer(pieces));
		structureManager.setStartForStructure(structure, start, chunk);
		AntCraft.LOGGER.info("AntCraft: {} placed, bounds {}", planned.key().identifier(), start.getBoundingBox());
	}

	/**
	 * The start's pieces on a cavern floor. First 96 tries in the start's own chunk row (as
	 * planned), then, if none fits (a narrow strip, a mostly solid stretch), 96 in the rows up to 3
	 * chunks either side with a smaller path margin, and last on the lava sea's surface (the portal's
	 * netherrack spreads over the lava, like vanilla's Nether ruined portals at the lava sea). The
	 * pieces stay within about 4 chunks of the start chunk, inside the 8-chunk reference range.
	 */
	static List<StructurePiece> build(Planned planned, RegistryAccess registryAccess, StructureTemplateManager templates) {
		DeepNetherTerrain terrain = planned.terrain();
		NetherLayout layout = terrain.layout();
		StripBounds bounds = terrainBounds(terrain);
		RandomSource random = new XoroshiroRandomSource(Strip.mix(planned.seed(), 17500 + planned.index() * 7L + planned.kind().ordinal() * 131L));
		int layer = planned.layer();
		int lava = layout.lavaTop(layer), top = layout.layerTop(layer);
		ChunkPos chunk = planned.chunk();
		for (int attempt = 0; attempt < 96; attempt++) {
			int x = bounds.minX() + 2 + random.nextInt(Math.max(1, bounds.width() - 4));
			int z = chunk.getMinBlockZ() + random.nextInt(16);
			if (!bounds.inside(x, z) || terrain.nearPath(layer, x, z, attempt < 48 ? 10 : 6)) continue;
			int y = terrain.floorAbove(layer, x, z, attempt % 2 == 0 ? lava + 3 : lava + 3 + random.nextInt(Math.max(1, (top - lava) / 3)), top - 16);
			if (y == Integer.MIN_VALUE) continue;
			List<StructurePiece> pieces = tryAt(planned, registryAccess, templates, random, bounds, top, x, y, z);
			if (!pieces.isEmpty()) return pieces;
		}
		// fallback 1: the neighbouring chunk rows, nearer the paths
		int z0 = Math.max(bounds.minZ() + 2, chunk.getMinBlockZ() - 48), z1 = Math.min(bounds.maxZ() - 2, chunk.getMaxBlockZ() + 48);
		for (int attempt = 0; attempt < 96 && z0 <= z1; attempt++) {
			int x = bounds.minX() + 2 + random.nextInt(Math.max(1, bounds.width() - 4));
			int z = z0 + random.nextInt(z1 - z0 + 1);
			if (!bounds.inside(x, z) || terrain.nearPath(layer, x, z, 3)) continue;
			int y = terrain.floorAbove(layer, x, z, lava + 2, top - 12);
			if (y == Integer.MIN_VALUE) continue;
			List<StructurePiece> pieces = tryAt(planned, registryAccess, templates, random, bounds, top, x, y, z);
			if (!pieces.isEmpty()) return pieces;
		}
		// fallback 2: on the lava sea, where the cavern is open above it
		for (int attempt = 0; attempt < 96 && z0 <= z1; attempt++) {
			int x = bounds.minX() + 2 + random.nextInt(Math.max(1, bounds.width() - 4));
			int z = z0 + random.nextInt(z1 - z0 + 1);
			if (!bounds.inside(x, z) || terrain.nearPath(layer, x, z, 3)) continue;
			boolean open = true;
			for (int y = lava + 1; y <= lava + 14 && open; y++) open = terrain.carved(layer, x, y, z);
			if (!open) continue;
			List<StructurePiece> pieces = tryAt(planned, registryAccess, templates, random, bounds, top, x, lava + 1, z);
			if (!pieces.isEmpty()) return pieces;
		}
		return List.of();
	}

	/** The start's pieces standing at (x, y, z), or an empty list when they do not fit the strip. */
	private static List<StructurePiece> tryAt(
		Planned planned, RegistryAccess registryAccess, StructureTemplateManager templates, RandomSource random, StripBounds bounds, int top, int x, int y, int z
	) {
		Rotation rotation = Rotation.getRandom(random);
		if (planned.kind() == Kind.FOSSIL) {
			StructurePiecesBuilder builder = new StructurePiecesBuilder();
			NetherFossilPieces.addPieces(templates, builder, random, new BlockPos(x, y, z));
			List<StructurePiece> pieces = builder.build().pieces();
			return insideStrip(bounds, pieces.getFirst().getBoundingBox()) ? pieces : List.of();
		}
		boolean giant = random.nextFloat() < 0.05F;
		Identifier location = Identifier.withDefaultNamespace(giant ? "ruined_portal/giant_portal_" + (1 + random.nextInt(3)) : "ruined_portal/portal_" + (1 + random.nextInt(10)));
		StructureTemplate template = templates.getOrCreate(location);
		Mirror mirror = random.nextFloat() < 0.5F ? Mirror.NONE : Mirror.FRONT_BACK;
		BlockPos pivot = new BlockPos(template.getSize().getX() / 2, 0, template.getSize().getZ() / 2);
		BlockPos origin = new BlockPos(x - pivot.getX(), y - 1, z - pivot.getZ());
		BoundingBox box = template.getBoundingBox(origin, rotation, pivot, mirror);
		// the mirror flips about the origin, not the pivot: centre the box on the floor found at (x, z) again
		origin = origin.offset(x - (box.minX() + box.maxX()) / 2, 0, z - (box.minZ() + box.maxZ()) / 2);
		box = template.getBoundingBox(origin, rotation, pivot, mirror);
		int dx = 0;
		if (box.minX() < bounds.minX() + 1) dx = bounds.minX() + 1 - box.minX();
		else if (box.maxX() > bounds.maxX() - 1) dx = bounds.maxX() - 1 - box.maxX();
		origin = origin.offset(dx, 0, 0);
		box = template.getBoundingBox(origin, rotation, pivot, mirror);
		if (!insideStrip(bounds, box) || box.maxY() >= top - 2) return List.of();
		RuinedPortalPiece.Properties properties = new RuinedPortalPiece.Properties(false, 0.0F, false, false, false, false);
		return List.of(new RuinedPortalPiece(registryAccess, templates, origin, RuinedPortalPiece.VerticalPlacement.IN_NETHER, properties, location, template, rotation, mirror, pivot));
	}

	private static StripBounds terrainBounds(DeepNetherTerrain terrain) {
		return terrain.bounds();
	}

	private static boolean insideStrip(StripBounds bounds, BoundingBox box) {
		return box.minX() >= bounds.minX() && box.maxX() <= bounds.maxX() && box.minZ() >= bounds.minZ() && box.maxZ() <= bounds.maxZ();
	}
}
