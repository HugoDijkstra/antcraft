package dev.antcraft;

import dev.antcraft.mixin.ScatteredFeaturePieceAccessor;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.IglooPieces;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftStructure;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinStructure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Where the tall layout's extra deep starts go (spec v2 3.6, 3.8): the hand-placed ones (temple,
 * hut, igloo, ocean ruins, the crags' ruined portal, the pyramid), the projected jigsaw copies
 * (trail ruins, camps, outpost), the Trial Vault and the mineshafts. Everything is a pure function
 * of seed and strip bounds, so the planned footprints are known before any chunk generates.
 */
public final class DeepStarts {
	private DeepStarts() {}

	public static ResourceKey<Structure> key(String path) {
		return ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, path));
	}

	public static final ResourceKey<Structure> TEMPLE = key("deep_jungle_temple"), HUT = key("deep_swamp_hut"), IGLOO = key("deep_igloo"),
		CRAGS_PORTAL = key("deep_ruined_portal_crags"), TRAIL_RUINS = key("deep_trail_ruins"), CAMP_DAPPLED = key("deep_abandoned_camp_dappled"),
		CAMP_PALE = key("deep_abandoned_camp_pale"), OUTPOST = key("deep_pillager_outpost");

	public static ResourceKey<Structure> oceanRuin(int i) {
		return key("deep_ocean_ruin_" + i);
	}

	public static ResourceKey<Structure> mineshaft(int layer) {
		return key("deep_mineshaft_layer_" + layer);
	}

	/** How far the pyramid's TNT room reaches below its box (DesertPyramidPiece). */
	static final int PYRAMID_TNT_DEPTH = 14;

	/** A hand-placed start: its chunk, how to build its pieces (fresh each time; pieces are mutable) and their boxes. */
	public record Planned(ResourceKey<Structure> key, int layer, ChunkPos chunk, Supplier<List<StructurePiece>> pieces, List<BoundingBox> footprint) {}

	// ------------------------------------------------------------------ chunk rows

	/**
	 * A chunk row of the strip for a deep start in {@code layer}: a seeded first try, preferring
	 * rows at least six away from the layer's village, three away from the rows in {@code avoid},
	 * and not under the hole of the floor above; never the first or last row.
	 */
	public static int pickRow(long seed, StripBounds bounds, StripLayout layout, int layer, long salt, List<Integer> avoid) {
		return pickRow(seed, bounds, layout, layer, salt, avoid, null);
	}

	/** As above; {@code vaultExitZ} is the Trial Vault's exit (the real hole over the layer below the vault), if known. */
	public static int pickRow(long seed, StripBounds bounds, StripLayout layout, int layer, long salt, List<Integer> avoid, @Nullable Integer vaultExitZ) {
		int firstRow = (bounds.minZ() >> 4) + 1, lastRow = (bounds.maxZ() >> 4) - 1;
		if (lastRow < firstRow) lastRow = firstRow;
		int rows = lastRow - firstRow + 1;
		Integer villageRow = layout.villageType(layer) != null ? layout.villageChunk(seed, layer, bounds).z() : null;
		boolean underVault = layout.vaultLayer >= 1 && layer - 1 == layout.vaultLayer && vaultExitZ != null;
		int holeZ = underVault ? vaultExitZ : layout.holeZ(seed, layer - 1, bounds);
		int start = (int) Math.floorMod(Strip.mix(seed, salt), (long) rows);
		int best = firstRow, bestScore = -1;
		for (int i = 0; i < rows; i++) {
			int row = firstRow + (start + i) % rows;
			int score = (villageRow == null ? 6 : Math.min(Math.abs(row - villageRow), 6)) * 4;
			int near = 3;
			for (int other : avoid) near = Math.min(near, Math.abs(row - other));
			// under the hole's fall line or its landing pool (a few blocks either side of the hole row)
			boolean underHole = holeZ >= row * 16 - 4 && holeZ <= row * 16 + 19;
			score += near * 2 + (underHole ? 0 : 1);
			if (score > bestScore) {
				bestScore = score;
				best = row;
			}
		}
		return best;
	}

	public static ChunkPos projectedChunk(long seed, StripBounds bounds, StripLayout layout, int layer, long salt) {
		return new ChunkPos(bounds.centerX() >> 4, pickRow(seed, bounds, layout, layer, salt, List.of()));
	}

	/** The Trial Vault's start z (spec 3.5.1): the vault can grow both ways along the strip from it. */
	public static int vaultZ(long seed, StripBounds bounds) {
		int length = bounds.length();
		return bounds.centerZ() + (int) Math.floorMod(Strip.mix(seed, 2006), (long) (length / 4 + 1)) - length / 8;
	}

	/** Chunk of a layer's mineshaft start (spec 3.8). */
	public static ChunkPos mineshaftChunk(long seed, StripBounds bounds, int layer) {
		int firstRow = bounds.minZ() >> 4, lastRow = bounds.maxZ() >> 4;
		int rows = lastRow - firstRow + 1;
		return new ChunkPos(bounds.centerX() >> 4, firstRow + 2 + (int) Math.floorMod(Strip.mix(seed, 9000 + layer), (long) Math.max(1, rows - 4)));
	}

	/** Mineshaft band of a layer: [b+12, b+72], layer 2's top lowered under the buried trail ruins. */
	public static int[] mineshaftBand(StripLayout layout, int layer) {
		int b = layout.layerBottom(layer);
		boolean trailRuins = layout.layer(layer).extraStructures().contains("deep_trail_ruins");
		return new int[] {b + 12, trailRuins ? b + 53 : b + 72};
	}

	// ------------------------------------------------------------------ hand-placed starts

	private static int layerOf(StripLayout layout, String extra) {
		for (int l = 1; l <= layout.deepLayers; l++) if (layout.layer(l).extraStructures().contains(extra)) return l;
		return -1;
	}

	/** The desert pyramid of the underground desert (also layout 1's): facing north or south, centred in the strip's width. */
	public static DesertPyramidPiece buildPyramid(long seed, StripBounds bounds, StripLayout layout, ChunkPos pos) {
		int west = bounds.centerX() - StripLayout.PYRAMID_SIZE / 2;
		int north = pos.getMinBlockZ() + StripLayout.PYRAMID_OFFSET_Z;
		RandomSource random = RandomSource.create(Strip.mix(seed, 8001));
		DesertPyramidPiece piece = new DesertPyramidPiece(random, west, north);
		for (int i = 0; i < 64 && piece.getOrientation() != Direction.NORTH && piece.getOrientation() != Direction.SOUTH; i++) {
			piece = new DesertPyramidPiece(random, west, north);
		}
		piece.move(0, layout.forestFloor(layout.desertLayer) - piece.getBoundingBox().minY(), 0);
		((ScatteredFeaturePieceAccessor) piece).antcraft$setHeightPosition(0);
		return piece;
	}

	/** Centres a scattered piece in the strip's width and sets it on the forest floor (its lowest row one above). */
	private static <T extends StructurePiece> T seat(T piece, StripBounds bounds, int y) {
		BoundingBox box = piece.getBoundingBox();
		piece.move(bounds.centerX() - (box.minX() + box.maxX()) / 2, y - box.minY(), 0);
		((ScatteredFeaturePieceAccessor) piece).antcraft$setHeightPosition(0);
		return piece;
	}

	/**
	 * Every hand-placed start of a tall layout (temple, hut, igloo, ocean ruins, crags portal,
	 * pyramid), in a fixed order. Layout 1 has none here (its pyramid is placed as before).
	 */
	public static List<Planned> plan(long seed, StripBounds bounds, StripLayout layout, DeepTerrain terrain, StructureTemplateManager templates, HolderLookup.Provider registries,
		@Nullable Integer vaultExitZ) {
		List<Planned> list = new ArrayList<>();
		if (layout.id < 2) return list;
		int cx = bounds.centerX() >> 4;

		int temple = layerOf(layout, "deep_jungle_temple");
		if (temple > 0) {
			ChunkPos chunk = new ChunkPos(cx, pickRow(seed, bounds, layout, temple, 8100, List.of(), vaultExitZ));
			int ff = layout.forestFloor(temple);
			list.add(planned(TEMPLE, temple, chunk, () -> List.of(seat(new JungleTemplePiece(rnd(seed, 8101), chunk.getMinBlockX(), chunk.getMinBlockZ() + 1), bounds, ff + 1))));
		}
		int hut = layerOf(layout, "deep_swamp_hut");
		if (hut > 0) {
			ChunkPos chunk = new ChunkPos(cx, pickRow(seed, bounds, layout, hut, 8200, List.of(), vaultExitZ));
			int ff = layout.forestFloor(hut);
			list.add(planned(HUT, hut, chunk, () -> List.of(seat(new SwampHutPiece(rnd(seed, 8201), chunk.getMinBlockX(), chunk.getMinBlockZ() + 3), bounds, ff + 1))));
		}
		int igloo = layerOf(layout, "deep_igloo");
		if (igloo > 0) {
			ChunkPos chunk = new ChunkPos(cx, pickRow(seed, bounds, layout, igloo, 8300, List.of(), vaultExitZ));
			int ff = layout.forestFloor(igloo);
			list.add(planned(IGLOO, igloo, chunk, () -> {
				RandomSource random = rnd(seed, 8301);
				Rotation rotation = Rotation.getRandom(random);
				StructurePiecesBuilder builder = new StructurePiecesBuilder();
				IglooPieces.addPieces(templates, new BlockPos(bounds.centerX() - 3, ff, chunk.getMinBlockZ() + 5), rotation, builder, random);
				List<StructurePiece> pieces = new ArrayList<>(builder.build().pieces());
				// centre the igloo top in the strip's width (the basement moves with it)
				StructurePiece top = pieces.getLast();
				BoundingBox box = top.getBoundingBox();
				int dx = bounds.centerX() - (box.minX() + box.maxX()) / 2;
				for (StructurePiece p : pieces) p.move(dx, 0, 0);
				return pieces;
			}));
		}
		int crags = layerOf(layout, "deep_ruined_portal_crags");
		if (crags > 0) {
			ChunkPos chunk = new ChunkPos(cx, pickRow(seed, bounds, layout, crags, 8400, List.of(), vaultExitZ));
			int ff = layout.forestFloor(crags);
			list.add(planned(CRAGS_PORTAL, crags, chunk, () -> {
				RandomSource random = rnd(seed, 8401);
				Identifier location = Identifier.withDefaultNamespace("ruined_portal/portal_" + (1 + random.nextInt(10)));
				StructureTemplate template = templates.getOrCreate(location);
				Rotation rotation = Rotation.getRandom(random);
				Mirror mirror = random.nextFloat() < 0.5F ? Mirror.NONE : Mirror.FRONT_BACK;
				BlockPos pivot = new BlockPos(template.getSize().getX() / 2, 0, template.getSize().getZ() / 2);
				BlockPos origin = new BlockPos(bounds.centerX() - pivot.getX(), ff, chunk.getMinBlockZ() + 4);
				BoundingBox box = template.getBoundingBox(origin, rotation, pivot, mirror);
				origin = origin.offset(bounds.centerX() - (box.minX() + box.maxX()) / 2, 0, 0);
				RuinedPortalPiece.Properties properties = new RuinedPortalPiece.Properties(false, 0.2F, false, false, false, false);
				return List.of(new RuinedPortalPiece(registries, templates, origin, RuinedPortalPiece.VerticalPlacement.IN_MOUNTAIN, properties, location, template, rotation, mirror, pivot));
			}));
		}
		int reef = layerOf(layout, "deep_ocean_ruin_0");
		if (reef > 0) {
			List<Integer> rows = new ArrayList<>();
			int water = terrain.waterLine(reef);
			for (int i = 0; i < 3; i++) {
				int row = pickRow(seed, bounds, layout, reef, 8500 + i, rows, vaultExitZ);
				rows.add(row);
				ChunkPos chunk = new ChunkPos(cx, row);
				int index = i;
				// the first site whose corners and centre lie in water at least 4 deep (never on an island)
				Supplier<List<StructurePiece>> build = () -> {
					RandomSource random = rnd(seed, 8510 + index);
					boolean large = random.nextFloat() < 0.3F;
					Identifier location = Identifier.withDefaultNamespace(large ? "underwater_ruin/big_warm_" + (4 + random.nextInt(4)) : "underwater_ruin/warm_" + (1 + random.nextInt(8)));
					for (int attempt = 0; attempt < 24; attempt++) {
						Rotation rotation = Rotation.getRandom(random);
						int x = bounds.minX() + 1 + random.nextInt(Math.max(1, bounds.width() - 2));
						int z = chunk.getMinBlockZ() + random.nextInt(16);
						OceanRuinPieces.OceanRuinPiece piece = new OceanRuinPieces.OceanRuinPiece(templates, location, new BlockPos(x, water, z), rotation, large ? 0.9F : 0.8F,
							OceanRuinStructure.Type.WARM, large);
						BoundingBox box = piece.getBoundingBox();
						if (box.minX() < bounds.minX() + 1 || box.maxX() > bounds.maxX() - 1 || box.minZ() < bounds.minZ() + 1 || box.maxZ() > bounds.maxZ() - 1) continue;
						if (!deepWater(terrain, reef, box, water)) continue;
						return List.of(piece);
					}
					return List.of();
				};
				list.add(planned(oceanRuin(i), reef, chunk, build));
			}
		}
		// the pyramid (its TNT room reaches 14 below the box)
		if (layout.layer(layout.desertLayer).extraStructures().contains("deep_desert_pyramid")) {
			ChunkPos chunk = layout.pyramidChunk(seed, bounds);
			Planned p = planned(StripChunkGenerator.DEEP_PYRAMID, layout.desertLayer, chunk, () -> List.of(buildPyramid(seed, bounds, layout, chunk)));
			List<BoundingBox> extended = new ArrayList<>();
			for (BoundingBox box : p.footprint()) extended.add(new BoundingBox(box.minX(), box.minY() - PYRAMID_TNT_DEPTH, box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
			list.add(new Planned(p.key(), p.layer(), p.chunk(), p.pieces(), List.copyOf(extended)));
		}
		return list;
	}

	private static boolean deepWater(DeepTerrain terrain, int layer, BoundingBox box, int water) {
		int[][] points = {{box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()},
			{(box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2}};
		for (int[] p : points) if (terrain.surfaceAt(layer, p[0], p[1]) > water - 4) return false;
		return true;
	}

	private static Planned planned(ResourceKey<Structure> key, int layer, ChunkPos chunk, Supplier<List<StructurePiece>> pieces) {
		List<BoundingBox> boxes = new ArrayList<>();
		for (StructurePiece piece : pieces.get()) boxes.add(piece.getBoundingBox());
		return new Planned(key, layer, chunk, pieces, List.copyOf(boxes));
	}

	private static RandomSource rnd(long seed, long salt) {
		return new XoroshiroRandomSource(Strip.mix(seed, salt));
	}

	// ------------------------------------------------------------------ mineshafts

	/**
	 * A layer's mineshaft: vanilla pieces grown from a room near the strip's west side, moved so the
	 * room sits at layer bottom + 40; pieces not wholly inside the band, outside the strip, or
	 * touching (inflated by 2) a planned footprint or the sealed zone under a sea are dropped.
	 */
	public static List<StructurePiece> buildMineshaft(long seed, StripBounds bounds, StripLayout layout, DeepTerrain terrain, int layer, List<BoundingBox> footprints) {
		LayerSpec spec = layout.layer(layer);
		MineshaftStructure.Type type = spec.mineshaft() == LayerSpec.Mineshaft.MESA ? MineshaftStructure.Type.MESA : MineshaftStructure.Type.NORMAL;
		ChunkPos chunk = mineshaftChunk(seed, bounds, layer);
		RandomSource random = rnd(seed, 9000 + layer);
		StructurePiecesBuilder builder = new StructurePiecesBuilder();
		MineshaftPieces.MineShaftRoom room = new MineshaftPieces.MineShaftRoom(0, random, bounds.centerX() - 4, chunk.getMinBlockZ() + 2, type);
		builder.addPiece(room);
		room.addChildren(room, builder, random);
		builder.offsetPiecesVertically(layout.layerBottom(layer) + 40 - room.getBoundingBox().minY());
		int[] band = mineshaftBand(layout, layer);
		int sealedBelow = spec.theme() != null && spec.theme().isWater() ? layout.forestFloor(layer) - 3 - 4 : Integer.MAX_VALUE;
		List<StructurePiece> kept = new ArrayList<>();
		for (StructurePiece piece : builder.build().pieces()) {
			BoundingBox box = piece.getBoundingBox();
			if (box.minY() < band[0] || box.maxY() > band[1]) continue;
			if (box.maxX() < bounds.minX() || box.minX() > bounds.maxX() || box.maxZ() < bounds.minZ() || box.minZ() > bounds.maxZ()) continue;
			if (box.maxY() + 2 >= sealedBelow) continue;
			BoundingBox inflated = box.inflatedBy(2);
			boolean clash = false;
			for (BoundingBox f : footprints) clash |= inflated.intersects(f);
			if (!clash) kept.add(piece);
		}
		return kept;
	}
}
