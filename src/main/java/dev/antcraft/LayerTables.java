package dev.antcraft;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import dev.antcraft.LayerSpec.Animals;
import dev.antcraft.LayerSpec.GroundPalette;
import dev.antcraft.LayerSpec.Mineshaft;
import dev.antcraft.LayerSpec.Ore;
import dev.antcraft.LayerSpec.PlantPalette;
import dev.antcraft.LayerSpec.Puddle;
import dev.antcraft.LayerSpec.TreeChoice;

/**
 * The {@link LayerSpec} rows of each {@link StripLayout}. Built on first use (they hold block
 * states). Layout 1's rows reproduce the original per-layer arrays and switches exactly; they must
 * never change.
 */
final class LayerTables {
	private LayerTables() {}

	/**
	 * Layout 1's ore table (one list shared by all eight rows, iterated in this order, so the random
	 * sequence of placeOres stays the same). Vein counts are per 16x16 chunk per layer. Each tool
	 * tier unlocks one layer deeper (layer 1: coal + copper, 2: iron, 3: gold, 4: redstone + lapis,
	 * 5: diamond, 6: obsidian + emerald, 7: ancient debris) and keeps appearing below; the earlier an
	 * ore unlocks, the more common it is.
	 */
	static final List<Ore> LAYOUT_1_ORES = List.of(
		Ore.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 1, 8, 24, -1, 14),
		Ore.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 1, 8, 10, 0, 10),
		Ore.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 2, 8, 9, 0, 9),
		Ore.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 3, 8, 6, 0, 8),
		Ore.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 4, 8, 5, 0, 8),
		Ore.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 4, 8, 4, 0, 7),
		Ore.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 5, 8, 3, 0, 6),
		Ore.of(Blocks.OBSIDIAN, Blocks.OBSIDIAN, 6, 8, 2, 0, 6),
		Ore.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 6, 8, 2, 0, 3),
		Ore.of(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 7, 8, 1, 0, 2)
	);

	private static ResourceKey<Structure> structure(String path) {
		return ResourceKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, path));
	}

	private static BlockState s(net.minecraft.world.level.block.Block block) {
		return block.defaultBlockState();
	}

	/** A layout-1 row: no mineshaft, no dungeons, a forest, no extra structures. */
	private static LayerSpec row1(
		StripLayout layout, int layer, LayerTheme theme, BlockState base, BlockState patch, double patchThreshold, ResourceKey<Biome> forest, ResourceKey<Biome> cave,
		double pondThreshold, BlockState pondBottom, BlockState light, Puddle puddle, List<TreeChoice> trees, int treeAttempts,
		PlantPalette plants, GroundPalette ground, Animals animals
	) {
		String village = layout.villageStructure(layer);
		return new LayerSpec(
			theme, base, patch, patchThreshold, forest, cave, village == null ? null : structure(village), pondThreshold, pondBottom, light, puddle,
			LAYOUT_1_ORES, trees, treeAttempts, plants, ground, animals, Mineshaft.NONE, 0.0, true, List.of()
		);
	}

	static LayerSpec[] layout1(StripLayout l) {
		BlockState deepslate = s(Blocks.DEEPSLATE), tuff = s(Blocks.TUFF), calcite = s(Blocks.CALCITE);
		BlockState glowstone = s(Blocks.GLOWSTONE), clay = s(Blocks.CLAY);
		ResourceKey<Biome> caves = Biomes.DRIPSTONE_CAVES;
		return new LayerSpec[] {
			// 0: the vanilla layer (only its base stone is used: floor 0's boss-room ceiling)
			new LayerSpec(null, deepslate, deepslate, 0.4, null, null, null, 0.5, clay, glowstone, Puddle.LAVA, List.of(), List.of(), 0,
				PlantPalette.MEADOW, GroundPalette.GRASS, Animals.FARM, Mineshaft.NONE, 0.0, false, List.of()),
			row1(l, 1, LayerTheme.OAK_HOLLOW, deepslate, tuff, 0.4, Biomes.FOREST, caves, 0.5, clay, glowstone, Puddle.WATER_OR_LAVA,
				List.of(new TreeChoice(TreeFeatures.OAK, 5), new TreeChoice(TreeFeatures.BIRCH, 3), new TreeChoice(TreeFeatures.FANCY_OAK, 2)), 9,
				PlantPalette.MEADOW, GroundPalette.GRASS, Animals.FARM),
			row1(l, 2, LayerTheme.TAIGA_DEEP, deepslate, tuff, 0.4, Biomes.TAIGA, caves, 0.5, clay, glowstone, Puddle.WATER_OR_LAVA,
				List.of(new TreeChoice(TreeFeatures.SPRUCE, 6), new TreeChoice(TreeFeatures.PINE, 3), new TreeChoice(TreeFeatures.MEGA_SPRUCE, 1)), 9,
				PlantPalette.TAIGA, GroundPalette.GRASS, Animals.FARM),
			row1(l, 3, LayerTheme.DARKWOOD, tuff, calcite, 0.4, Biomes.DARK_FOREST, caves, 0.5, clay, glowstone, Puddle.LAVA,
				List.of(new TreeChoice(TreeFeatures.DARK_OAK, 8), new TreeChoice(TreeFeatures.OAK, 2)), 14,
				PlantPalette.MEADOW, GroundPalette.GRASS, Animals.FARM),
			row1(l, 4, LayerTheme.JUNGLE, s(Blocks.DRIPSTONE_BLOCK), calcite, 0.4, Biomes.JUNGLE, caves, 0.5, clay, glowstone, Puddle.LAVA,
				List.of(new TreeChoice(TreeFeatures.JUNGLE_TREE, 6), new TreeChoice(TreeFeatures.MEGA_JUNGLE_TREE, 2), new TreeChoice(TreeFeatures.JUNGLE_BUSH, 2)), 14,
				PlantPalette.MEADOW, GroundPalette.GRASS, Animals.FARM),
			row1(l, 5, LayerTheme.DEEP_DARK, deepslate, s(Blocks.SCULK), 0.4, Biomes.CHERRY_GROVE, Biomes.DEEP_DARK, 0.5, clay, glowstone, Puddle.LAVA,
				List.of(new TreeChoice(TreeFeatures.CHERRY, 1)), 9,
				PlantPalette.CHERRY, GroundPalette.GRASS, Animals.FARM),
			row1(l, 6, LayerTheme.BURIED_DESERT, s(Blocks.SANDSTONE), s(Blocks.RED_SANDSTONE), 0.4, Biomes.DESERT, caves, 0.66, s(Blocks.SAND), glowstone, Puddle.LAVA,
				List.of(), 0,
				PlantPalette.DESERT, GroundPalette.SAND, Animals.DESERT),
			row1(l, 7, LayerTheme.MANGROVE, s(Blocks.BLACKSTONE), s(Blocks.MAGMA_BLOCK), 0.62, Biomes.MANGROVE_SWAMP, caves, 0.25, clay, glowstone, Puddle.LAVA,
				List.of(new TreeChoice(TreeFeatures.MANGROVE, 3), new TreeChoice(TreeFeatures.TALL_MANGROVE, 1)), 9,
				PlantPalette.SWAMP, GroundPalette.MUD_AND_GRASS, Animals.FARM),
			row1(l, 8, LayerTheme.MUSHROOM, deepslate, s(Blocks.BLACKSTONE), 0.4, Biomes.MUSHROOM_FIELDS, caves, 0.5, clay, s(Blocks.SHROOMLIGHT), Puddle.LAVA,
				List.of(new TreeChoice(TreeFeatures.HUGE_BROWN_MUSHROOM, 1), new TreeChoice(TreeFeatures.HUGE_RED_MUSHROOM, 1)), 5,
				PlantPalette.MUSHROOM, GroundPalette.MYCELIUM, Animals.MOOSHROOM),
		};
	}
}
