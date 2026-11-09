package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Layout 1's block and feature tables equal the original arrays and switches ({@link LegacyGeometry.Tables}). */
class LayoutTablesTest {
	private static final StripLayout L = StripLayout.LAYOUT_1;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void stonesAndFluidsEqualTheOriginal() {
		for (int l = 0; l <= 8; l++) {
			LayerSpec spec = L.layer(l);
			assertSame(LegacyGeometry.Tables.BASE_STONE[l], spec.baseStone(), "base stone " + l);
			assertSame(LegacyGeometry.Tables.PATCH_STONE[l], spec.patchStone(), "patch stone " + l);
			if (l == 0) continue;
			assertEquals(LegacyGeometry.Tables.patchThreshold(l), spec.patchThreshold(), "patch threshold " + l);
			assertEquals(LegacyGeometry.Tables.pondThreshold(l), spec.pondThreshold(), "pond threshold " + l);
			assertSame(LegacyGeometry.Tables.pondBottom(l), spec.pondBottom(), "pond bottom " + l);
			assertSame(LegacyGeometry.Tables.light(l), spec.lightBlock(), "light " + l);
			assertEquals(LegacyGeometry.Tables.waterPuddles(l), spec.puddle() == LayerSpec.Puddle.WATER_OR_LAVA, "puddle " + l);
			assertEquals(LayerSpec.Mineshaft.NONE, spec.mineshaft());
			assertEquals(0.0, spec.dungeonRate());
			assertTrue(spec.hasForest());
			assertTrue(spec.extraStructures().isEmpty());
		}
	}

	@Test
	void themesReplaceTheOldLayerLiterals() {
		LayerTheme[] expected = {
			null, LayerTheme.OAK_HOLLOW, LayerTheme.TAIGA_DEEP, LayerTheme.DARKWOOD, LayerTheme.JUNGLE, LayerTheme.DEEP_DARK, LayerTheme.BURIED_DESERT,
			LayerTheme.MANGROVE, LayerTheme.MUSHROOM,
		};
		for (int l = 1; l <= 8; l++) {
			LayerTheme theme = L.layer(l).theme();
			assertSame(expected[l], theme, "theme " + l);
			assertEquals(l == LegacyGeometry.DESERT_LAYER, theme.isDesert(), "desert " + l);
			assertEquals(l == 7, theme.isSwamp(), "swamp " + l);
			assertEquals(l == 8, theme.isMushroom(), "mushroom " + l);
			assertEquals(l == 4, theme.ceilingGlowBerries(), "glow berries " + l);
			assertEquals(l == LegacyGeometry.CITY_LAYER, theme.hasCity(), "city " + l);
			assertEquals(false, theme.isWater() || theme.isSolid(), "water/solid " + l);
			assertEquals("layer_" + l, theme.advancementId);
			assertEquals("antcraft.layer." + l, theme.langKey);
			// ground, plants and animals: the old switch (6 sand/desert, 7 mud/swamp, 8 mycelium/mushrooms, 2 taiga, 5 cherry)
			LayerSpec spec = L.layer(l);
			assertEquals(switch (l) { case 6 -> LayerSpec.GroundPalette.SAND; case 7 -> LayerSpec.GroundPalette.MUD_AND_GRASS; case 8 -> LayerSpec.GroundPalette.MYCELIUM; default -> LayerSpec.GroundPalette.GRASS; },
				spec.ground(), "ground " + l);
			assertEquals(switch (l) { case 2 -> LayerSpec.PlantPalette.TAIGA; case 5 -> LayerSpec.PlantPalette.CHERRY; case 6 -> LayerSpec.PlantPalette.DESERT;
				case 7 -> LayerSpec.PlantPalette.SWAMP; case 8 -> LayerSpec.PlantPalette.MUSHROOM; default -> LayerSpec.PlantPalette.MEADOW; }, spec.plants(), "plants " + l);
			assertEquals(l == 8 ? LayerSpec.Animals.MOOSHROOM : l == 6 ? LayerSpec.Animals.DESERT : LayerSpec.Animals.FARM, spec.animals(), "animals " + l);
		}
		assertNull(L.layer(0).theme());
	}

	@Test
	void oresEqualTheOriginalList() {
		List<LayerSpec.Ore> ores = L.layer(1).ores();
		assertEquals(LegacyGeometry.Tables.ORES.size(), ores.size());
		for (int i = 0; i < ores.size(); i++) {
			LegacyGeometry.Tables.Ore old = LegacyGeometry.Tables.ORES.get(i);
			LayerSpec.Ore ore = ores.get(i);
			assertSame(old.stone(), ore.stone(), "ore " + i);
			assertSame(old.deepslate(), ore.deepslate(), "ore " + i);
			assertEquals(old.minLayer(), ore.minLayer(), "ore " + i);
			assertEquals(old.maxLayer(), ore.maxLayer(), "ore " + i);
			assertEquals(old.veins(), ore.veins(), "ore " + i);
			assertEquals(old.perLayer(), ore.perLayer(), "ore " + i);
			assertEquals(old.size(), ore.size(), "ore " + i);
		}
		// one list object shared by all rows: placeOres walks the same table in every layer
		for (int l = 1; l <= 8; l++) assertSame(ores, L.layer(l).ores(), "ores " + l);
	}

	@Test
	void treesEqualTheOriginal() {
		for (int l = 1; l <= 8; l++) {
			List<LegacyGeometry.Tables.TreeChoice> old = LegacyGeometry.Tables.trees(l);
			List<LayerSpec.TreeChoice> trees = L.layer(l).trees();
			assertEquals(old.size(), trees.size(), "trees " + l);
			for (int i = 0; i < old.size(); i++) {
				assertEquals(old.get(i).feature(), trees.get(i).feature(), "tree " + l + "/" + i);
				assertEquals(old.get(i).weight(), trees.get(i).weight(), "tree " + l + "/" + i);
			}
			assertEquals(LegacyGeometry.Tables.treeAttempts(l), L.layer(l).treeAttempts(), "attempts " + l);
		}
	}

	@Test
	void biomesAndVillagesEqualTheOriginal() {
		List<ResourceKey<Biome>> forests = List.of(
			Biomes.FOREST, Biomes.TAIGA, Biomes.DARK_FOREST, Biomes.JUNGLE, Biomes.CHERRY_GROVE, Biomes.DESERT, Biomes.MANGROVE_SWAMP, Biomes.MUSHROOM_FIELDS
		);
		for (int l = 1; l <= 8; l++) {
			LayerSpec spec = L.layer(l);
			assertEquals(forests.get(l - 1), spec.forestBiome(), "forest biome " + l);
			assertEquals(l == LegacyGeometry.CITY_LAYER ? Biomes.DEEP_DARK : Biomes.DRIPSTONE_CAVES, spec.caveBiome(), "cave biome " + l);
			assertNotNull(spec.villageStructure());
			assertEquals(Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, "deep_village_" + LegacyGeometry.villageType(l)), spec.villageStructure().identifier());
		}
	}

	@Test
	void possibleBiomesKeepTheirOrder() {
		HolderOwner<Biome> owner = new HolderOwner<>() {};
		List<Holder.Reference<Biome>> holders = new ArrayList<>();
		for (String name : List.of("plains", "caves", "forest", "taiga", "dark", "jungle", "cherry", "desert", "mangrove", "mushroom", "deep_dark")) {
			holders.add(Holder.Reference.createStandAlone(owner, ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("test", name))));
		}
		StripBiomeSource source = new StripBiomeSource(
			new FixedBiomeSource(holders.get(0)), holders.get(1), holders.get(2), holders.get(3), holders.get(4), holders.get(5), holders.get(6),
			holders.get(7), holders.get(8), holders.get(9), holders.get(10), 1, null
		);
		// today's order: the vanilla source's biomes, then caves, deep dark, the eight forest biomes
		List<Holder<Biome>> expected = List.of(
			holders.get(0), holders.get(1), holders.get(10), holders.get(2), holders.get(3), holders.get(4), holders.get(5), holders.get(6), holders.get(7),
			holders.get(8), holders.get(9)
		);
		assertEquals(expected, List.copyOf(source.possibleBiomes()));
	}

	@Test
	void guardianThemesEqualTheOriginal() {
		for (int floor : LegacyGeometry.GUARDIAN_FLOORS) {
			GuardianId guardian = L.guardian(floor);
			assertNotNull(guardian, "floor " + floor);
			LegacyGeometry.Tables.Theme old = LegacyGeometry.Tables.theme(floor);
			GuardianId.Theme theme = guardian.theme();
			assertSame(old.pillar(), theme.pillar(), "pillar " + floor);
			assertSame(old.accentPillar(), theme.accentPillar(), "accent pillar " + floor);
			assertSame(old.carpet(), theme.carpet(), "carpet " + floor);
			assertSame(old.banner(), theme.banner(), "banner " + floor);
			assertSame(old.lantern(), theme.lantern(), "lantern " + floor);
			assertSame(old.accent(), theme.accent(), "accent " + floor);
			assertEquals(floor == 6, theme.candles(), "candles " + floor);
			assertEquals(false, theme.extraCobwebs(), "cobwebs " + floor);
			assertEquals("guardian_" + floor, guardian.advancementId);
			assertEquals("antcraft.guardian." + floor, guardian.langKey);
		}
		for (Object[] row : LegacyGeometry.Tables.GUARDIANS) {
			GuardianId guardian = L.guardian((Integer) row[0]);
			assertEquals(row[1], guardian.englishName);
			assertEquals((Double) row[2], guardian.health(1));
			assertEquals((Double) row[3], guardian.scale);
			assertEquals(0.6, guardian.knockbackResistance);
		}
		assertSame(net.minecraft.world.entity.EntityTypes.VINDICATOR, GuardianId.TUNNEL_CAPTAIN.entityType());
		assertSame(net.minecraft.world.entity.EntityTypes.RAVAGER, GuardianId.DEEP_BEAST.entityType());
		assertSame(net.minecraft.world.entity.EntityTypes.HUSK, GuardianId.DESERT_COLOSSUS.entityType());
		// every guardian has a theme (layouts 2 and 3)
		for (GuardianId g : GuardianId.values()) assertNotNull(g.theme().pillar(), g.name());
		assertSame(Blocks.DEEPSLATE.defaultBlockState(), L.layer(0).baseStone());
	}
}
