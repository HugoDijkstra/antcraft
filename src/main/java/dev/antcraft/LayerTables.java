package dev.antcraft;

import java.util.List;
import net.minecraft.core.registries.Registries;
import java.util.ArrayList;
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

	// ------------------------------------------------------------------ layout 2 (spec v2, sections 3.2 and 3.4)

	/**
	 * Layout 2's main ore table: layout 1's seven tool steps spread over 19 layers in the same
	 * proportion (unlock layers 1, 3, 5, 8, 10, 12, 15).
	 */
	static final List<Ore> LAYOUT_2_ORES = List.of(
		Ore.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 1, 19, 24, -1, 14),
		Ore.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 1, 19, 10, 0, 10),
		Ore.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 3, 19, 9, 0, 9),
		Ore.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 5, 19, 6, 0, 8),
		Ore.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 8, 19, 5, 0, 8),
		Ore.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 8, 19, 4, 0, 7),
		Ore.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 10, 19, 3, 0, 6),
		Ore.of(Blocks.OBSIDIAN, Blocks.OBSIDIAN, 12, 19, 2, 0, 6),
		Ore.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 12, 19, 2, 0, 3),
		Ore.of(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 15, 19, 1, 0, 2)
	);

	/** Layout 2's bonus ores, by layer (they come after the main table). */
	private static List<Ore> bonusOres(int layer) {
		return switch (layer) {
			case 4 -> List.of(Ore.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 4, 4, 4, 0, 8));
			case 7 -> List.of(Ore.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 7, 7, 8, 0, 10));
			case 11 -> List.of(Ore.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 11, 11, 3, 0, 3));
			case 14 -> List.of(Ore.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 14, 14, 3, 0, 8));
			case 15 -> List.of(Ore.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 15, 15, 3, 0, 7));
			case 18 -> List.of(
				Ore.of(Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_QUARTZ_ORE, 18, 18, 6, 0, 8),
				Ore.of(Blocks.NETHER_GOLD_ORE, Blocks.NETHER_GOLD_ORE, 18, 18, 4, 0, 6),
				Ore.of(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS, 18, 18, 1, 0, 2)
			);
			default -> List.of();
		};
	}

	/** Layout 2's ores for one layer: the main table, then that layer's bonus rows. */
	static List<Ore> layout2Ores(int layer) {
		List<Ore> bonus = bonusOres(layer);
		if (bonus.isEmpty()) return LAYOUT_2_ORES;
		List<Ore> all = new ArrayList<>(LAYOUT_2_ORES);
		all.addAll(bonus);
		return List.copyOf(all);
	}

	/** Dungeon attempts per 256 strip columns: rare near the top, common deep down (section 3.9). */
	static double dungeonRate2(int layer) {
		return 0.4 + 2.6 * (layer - 1) / 18.0;
	}

	private static final List<String> NO_EXTRAS = List.of();

	private static LayerSpec row2(
		StripLayout layout, int layer, LayerTheme theme, BlockState base, BlockState patch, double patchThreshold, ResourceKey<Biome> forest, ResourceKey<Biome> cave,
		double pondThreshold, BlockState pondBottom, BlockState light, Puddle puddle, List<TreeChoice> trees, int treeAttempts,
		PlantPalette plants, GroundPalette ground, Animals animals, Mineshaft mineshaft, List<String> extras
	) {
		String village = layout.villageStructure(layer);
		boolean forestLayer = !theme.isSolid();
		return new LayerSpec(
			theme, base, patch, patchThreshold, forest, cave, village == null ? null : structure(village), pondThreshold, pondBottom, light, puddle,
			layout2Ores(layer), trees, treeAttempts, plants, ground, animals, mineshaft, forestLayer ? dungeonRate2(layer) : 0.0, forestLayer, extras
		);
	}

	private static List<TreeChoice> trees(Object... pairs) {
		List<TreeChoice> list = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			@SuppressWarnings("unchecked")
			ResourceKey<net.minecraft.world.level.levelgen.feature.Feature> key = (ResourceKey<net.minecraft.world.level.levelgen.feature.Feature>) pairs[i];
			list.add(new TreeChoice(key, (Integer) pairs[i + 1]));
		}
		return List.copyOf(list);
	}

	static LayerSpec[] layout2(StripLayout l) {
		BlockState deepslate = s(Blocks.DEEPSLATE), tuff = s(Blocks.TUFF), calcite = s(Blocks.CALCITE), clay = s(Blocks.CLAY), sand = s(Blocks.SAND);
		BlockState glowstone = s(Blocks.GLOWSTONE), shroomlight = s(Blocks.SHROOMLIGHT);
		ResourceKey<Biome> caves = Biomes.DRIPSTONE_CAVES;
		Puddle wl = Puddle.WATER_OR_LAVA, lava = Puddle.LAVA;
		Mineshaft normal = Mineshaft.NORMAL, mesa = Mineshaft.MESA, none = Mineshaft.NONE;
		return new LayerSpec[] {
			// 0: the vanilla band (only its base stone is used: floor 0's boss-room ceiling)
			new LayerSpec(null, deepslate, deepslate, 0.4, null, null, null, 0.5, clay, glowstone, Puddle.LAVA, List.of(), List.of(), 0,
				PlantPalette.MEADOW, GroundPalette.GRASS, Animals.FARM, Mineshaft.NONE, 0.0, false, List.of()),
			row2(l, 1, LayerTheme.OAK_HOLLOW, deepslate, tuff, 0.4, Biomes.FOREST, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.OAK, 5, TreeFeatures.BIRCH, 3, TreeFeatures.FANCY_OAK, 2), 9, PlantPalette.MEADOW, GroundPalette.GRASS, Animals.FARM, normal, NO_EXTRAS),
			row2(l, 2, LayerTheme.TAIGA_DEEP, s(Blocks.ANDESITE), s(Blocks.MOSSY_COBBLESTONE), 0.45, Biomes.TAIGA, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.SPRUCE, 6, TreeFeatures.PINE, 3, TreeFeatures.MEGA_SPRUCE, 1), 9, PlantPalette.TAIGA, GroundPalette.PODZOL_MIX, Animals.FARM, normal,
				List.of("deep_trail_ruins")),
			row2(l, 3, LayerTheme.AUTUMN_HOLLOW, s(Blocks.GRANITE), tuff, 0.4, Biomes.DAPPLED_FOREST, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.ORANGE_POPLAR_LEAF_LITTER, 5, TreeFeatures.RED_POPLAR_LEAF_LITTER, 4, TreeFeatures.YELLOW_POPLAR_LEAF_LITTER, 2,
					TreeFeatures.FALLEN_POPLAR_TREE, 2, TreeFeatures.SPRUCE, 1), 9, PlantPalette.AUTUMN, GroundPalette.COARSE_MIX, Animals.FARM, normal,
				List.of("deep_abandoned_camp_dappled")),
			row2(l, 4, LayerTheme.OCHRE_BADLANDS, s(Blocks.TERRACOTTA), s(Blocks.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.ORANGE)), 0.4, Biomes.BADLANDS, caves,
				0.5, clay, glowstone, lava, trees(TreeFeatures.OAK, 1), 2, PlantPalette.BADLANDS, GroundPalette.RED_SAND, Animals.FARM, mesa, NO_EXTRAS),
			row2(l, 5, LayerTheme.DARKWOOD, s(Blocks.SMOOTH_BASALT), s(Blocks.COBBLED_DEEPSLATE), 0.4, Biomes.DARK_FOREST, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.DARK_OAK, 8, TreeFeatures.OAK, 1, TreeFeatures.HUGE_BROWN_MUSHROOM, 1), 14, PlantPalette.DARKWOOD, GroundPalette.GRASS, Animals.FARM, mesa,
				List.of("deep_pillager_outpost")),
			row2(l, 6, LayerTheme.LUSH_GROTTO, deepslate, s(Blocks.MOSS_BLOCK), 0.4, Biomes.LUSH_CAVES, Biomes.LUSH_CAVES, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.AZALEA_TREE, 6), 9, PlantPalette.LUSH, GroundPalette.MOSS, Animals.FARM, normal, NO_EXTRAS),
			row2(l, 7, LayerTheme.TRIAL_VAULT, tuff, deepslate, 0.45, caves, caves, 0.5, clay, glowstone, wl,
				List.of(), 0, PlantPalette.NONE, GroundPalette.GRASS, Animals.NONE, none, List.of("deep_trial_vault")),
			row2(l, 8, LayerTheme.JUNGLE, s(Blocks.DRIPSTONE_BLOCK), calcite, 0.4, Biomes.JUNGLE, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.JUNGLE_TREE, 6, TreeFeatures.MEGA_JUNGLE_TREE, 2, TreeFeatures.JUNGLE_BUSH, 2), 14, PlantPalette.JUNGLE, GroundPalette.GRASS, Animals.FARM,
				normal, List.of("deep_jungle_temple")),
			row2(l, 9, LayerTheme.SULFUR_SPRINGS, s(Blocks.SULFUR), s(Blocks.CINNABAR), 0.4, Biomes.SAVANNA, Biomes.SULFUR_CAVES, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.ACACIA, 6, TreeFeatures.OAK, 1), 9, PlantPalette.SAVANNA, GroundPalette.COARSE_MIX, Animals.FARM, normal, NO_EXTRAS),
			row2(l, 10, LayerTheme.PALE_HOLLOW, s(Blocks.DIORITE), s(Blocks.PALE_MOSS_BLOCK), 0.4, Biomes.PALE_GARDEN, caves, 0.5, clay, s(Blocks.PEARLESCENT_FROGLIGHT), lava,
				trees(TreeFeatures.PALE_OAK, 6, TreeFeatures.PALE_OAK_CREAKING, 1), 9, PlantPalette.PALE, GroundPalette.PALE_MOSS, Animals.NONE, normal,
				List.of("deep_abandoned_camp_pale")),
			row2(l, 11, LayerTheme.STONY_CRAGS, s(Blocks.STONE), calcite, 0.4, Biomes.STONY_PEAKS, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.AZALEA_TREE, 1), 2, PlantPalette.CRAGS, GroundPalette.STONE_MIX, Animals.FARM, normal, List.of("deep_ruined_portal_crags")),
			row2(l, 12, LayerTheme.DEEP_DARK, deepslate, s(Blocks.SCULK), 0.4, Biomes.DEEP_DARK, Biomes.DEEP_DARK, 0.5, clay, s(Blocks.VERDANT_FROGLIGHT), lava,
				List.of(), 0, PlantPalette.NONE, GroundPalette.SCULK, Animals.NONE, none, List.of("deep_ancient_city")),
			row2(l, 13, LayerTheme.CRYSTAL_HOLLOWS, calcite, s(Blocks.AMETHYST_BLOCK), 0.6, Biomes.CHERRY_GROVE, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.CHERRY_BEES_005, 1), 9, PlantPalette.CHERRY, GroundPalette.GRASS, Animals.FARM, normal, NO_EXTRAS),
			row2(l, 14, LayerTheme.BURIED_DESERT, s(Blocks.SANDSTONE), s(Blocks.RED_SANDSTONE), 0.4, Biomes.DESERT, caves, 0.66, sand, glowstone, lava,
				List.of(), 0, PlantPalette.DESERT, GroundPalette.SAND, Animals.DESERT, normal, List.of("deep_desert_pyramid")),
			row2(l, 15, LayerTheme.SUNKEN_REEF, s(Blocks.PRISMARINE), s(Blocks.DARK_PRISMARINE), 0.4, Biomes.WARM_OCEAN, caves, 0.5, sand, s(Blocks.SEA_LANTERN), wl,
				trees(TreeFeatures.JUNGLE_BUSH, 1), 1, PlantPalette.NONE, GroundPalette.SAND, Animals.NONE, normal,
				List.of("deep_ocean_ruin_0", "deep_ocean_ruin_1", "deep_ocean_ruin_2")),
			row2(l, 16, LayerTheme.MANGROVE, s(Blocks.PACKED_MUD), s(Blocks.MUD), 0.55, Biomes.MANGROVE_SWAMP, caves, 0.25, clay, s(Blocks.OCHRE_FROGLIGHT), wl,
				trees(TreeFeatures.MANGROVE, 3, TreeFeatures.TALL_MANGROVE, 1), 9, PlantPalette.SWAMP, GroundPalette.MUD_AND_GRASS, Animals.FARM, normal,
				List.of("deep_swamp_hut")),
			row2(l, 17, LayerTheme.FROZEN_DEPTHS, deepslate, s(Blocks.PACKED_ICE), 0.4, Biomes.ICE_SPIKES, caves, 0.5, clay, glowstone, wl,
				trees(TreeFeatures.PINE, 1), 3, PlantPalette.NONE, GroundPalette.SNOW, Animals.FARM, normal, List.of("deep_igloo")),
			row2(l, 18, LayerTheme.EMBER_RIFT, s(Blocks.BLACKSTONE), s(Blocks.MAGMA_BLOCK), 0.62, EMBER_RIFT, caves, 0.5, clay, shroomlight, lava,
				trees(TreeFeatures.WARPED_FUNGUS_PLANTED, 5), 9, PlantPalette.EMBER, GroundPalette.WARPED_NYLIUM, Animals.NONE, mesa, NO_EXTRAS),
			row2(l, 19, LayerTheme.MUSHROOM, deepslate, s(Blocks.BLACKSTONE), 0.4, Biomes.MUSHROOM_FIELDS, caves, 0.5, clay, shroomlight, lava,
				trees(TreeFeatures.HUGE_BROWN_MUSHROOM, 1, TreeFeatures.HUGE_RED_MUSHROOM, 1), 5, PlantPalette.MUSHROOM, GroundPalette.MYCELIUM, Animals.MOOSHROOM, none,
				NO_EXTRAS),
		};
	}

	/** AntCraft's data biome for the Ember Rift (warped-forest look, no features). */
	static final ResourceKey<Biome> EMBER_RIFT = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "ember_rift"));
}
