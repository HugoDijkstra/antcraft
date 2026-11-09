package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Layout 2 (the tall world, spec v2 sections 3.1-3.4): geometry and layer table. */
class TallLayoutTest {
	private static final StripLayout L = StripLayout.LAYOUT_2;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void floorsMatchTheSpecTable() {
		int[] bases = {1632, 1436, 1240, 1044, 848, 652, 456, 328, 132, -64, -260, -456, -652, -848, -1044, -1240, -1436, -1632, -1828};
		assertEquals(19, L.floorCount);
		assertEquals(19, L.deepLayers);
		for (int k = 0; k < bases.length; k++) {
			assertEquals(bases[k], L.floorBase(k), "floor " + k);
			assertEquals(bases[k] + 2, L.floorTop(k));
		}
		assertSame(L, StripLayout.byId(2));
		assertEquals(1696, L.frameOffset);
		assertEquals(-2032, L.worldMinY);
		assertEquals(2031, L.worldMaxY);
		assertEquals(1632, L.vanillaMinY);
	}

	@Test
	void layersAndForestFloors() {
		int[] forest = {0, 1525, 1329, 1133, 937, 741, 545, -1, 221, 25, -171, -367, -563, -759, -955, -1151, -1347, -1543, -1739, -1938};
		for (int l = 1; l <= 19; l++) {
			int height = L.layerTop(l) - L.layerBottom(l) + 1;
			int expected = l == 7 ? 125 : l == 19 ? 199 : 193;
			assertEquals(expected, height, "layer " + l + " height");
			if (l != 7) assertEquals(forest[l], L.forestFloor(l), "forest floor " + l);
			for (int y = L.layerBottom(l); y <= L.layerTop(l); y++) assertEquals(l, L.layerAt(y));
		}
		assertEquals(331, L.layerBottom(7));
		assertEquals(455, L.layerTop(7));
		assertEquals(-2027, L.layerBottom(19));
		assertEquals(-1829, L.layerTop(19));
		assertEquals(-2027, L.portalRoomFloorY());
		assertEquals(0, L.layerAt(1635));
		assertEquals(-1, L.layerAt(1634));
		assertEquals(-1, L.layerAt(-2030));
	}

	@Test
	void gatesAndSpecialLayers() {
		assertEquals(12, L.cityLayer);
		assertEquals(-597, L.cityStartY);
		assertEquals(683, L.cityAnchorDelta);
		assertEquals(5, L.sealedFloor);
		assertEquals(13, L.ancientSealedFloor);
		assertEquals(16, L.emberSealedFloor);
		assertEquals(7, L.vaultLayer);
		assertEquals(377, L.vaultStartY); // spec 385, lowered by 8 by the 3.5.6 gate
		assertEquals(List.of(0, 2, 4, 8, 11, 14, 18), java.util.Arrays.stream(L.guardianFloors()).boxed().toList());
		assertEquals(GuardianId.EMBER_LORD, L.guardian(18));
		assertEquals(14, L.desertLayer);
		assertTrue(L.isVault(7));
		assertFalse(L.isVault(6));
		assertEquals(8f, L.hardnessScale(19));
		assertEquals(8, L.scaledLayer(19));
		assertEquals(1, L.scaledLayer(1));
		// layer 19's floor sits far from the portal room (hole avoidance works for the last floor)
		StripBounds bounds = StripBounds.of(20, 300);
		for (long seed = 0; seed < 50; seed++) {
			int room = L.portalRoomZ(seed, bounds);
			assertTrue(Math.abs(L.holeZ(seed, 18, bounds) - room) > PortalRoom.HALF_LENGTH + 3, "seed " + seed);
		}
	}

	@Test
	void layerTableRows() {
		LayerTheme[] themes = {
			null, LayerTheme.OAK_HOLLOW, LayerTheme.TAIGA_DEEP, LayerTheme.AUTUMN_HOLLOW, LayerTheme.OCHRE_BADLANDS, LayerTheme.DARKWOOD, LayerTheme.LUSH_GROTTO,
			LayerTheme.TRIAL_VAULT, LayerTheme.JUNGLE, LayerTheme.SULFUR_SPRINGS, LayerTheme.PALE_HOLLOW, LayerTheme.STONY_CRAGS, LayerTheme.DEEP_DARK,
			LayerTheme.CRYSTAL_HOLLOWS, LayerTheme.BURIED_DESERT, LayerTheme.SUNKEN_REEF, LayerTheme.MANGROVE, LayerTheme.FROZEN_DEPTHS, LayerTheme.EMBER_RIFT,
			LayerTheme.MUSHROOM,
		};
		Set<String> stonePairs = new HashSet<>();
		Set<Object> forests = new HashSet<>();
		Set<Object> trees = new HashSet<>();
		for (int l = 1; l <= 19; l++) {
			LayerSpec row = L.layer(l);
			assertSame(themes[l], row.theme(), "theme " + l);
			assertTrue(stonePairs.add(row.baseStone() + "/" + row.patchStone()), "stone pair " + l);
			assertTrue(forests.add(row.forestBiome()), "forest biome " + l);
			if (!row.trees().isEmpty()) assertTrue(trees.add(row.trees()), "trees " + l);
			assertEquals(l != 7, row.hasForest());
			assertNotNull(row.caveBiome());
		}
		assertSame(Blocks.TUFF.defaultBlockState(), L.layer(7).baseStone());
		assertTrue(L.layer(15).theme().isWater());
		assertTrue(L.layer(7).theme().isSolid());
		assertEquals("deep_village_layer_9", L.layer(9).villageStructure().identifier().getPath());
		for (int l : new int[] {3, 4, 6, 7, 8, 10, 11, 12, 15, 18}) assertEquals(null, L.layer(l).villageStructure(), "village " + l);
		// mineshafts: mesa in 4, 5, 18; none in 7, 12, 19
		for (int l = 1; l <= 19; l++) {
			LayerSpec.Mineshaft m = L.layer(l).mineshaft();
			LayerSpec.Mineshaft want = l == 4 || l == 5 || l == 18 ? LayerSpec.Mineshaft.MESA : l == 7 || l == 12 || l == 19 ? LayerSpec.Mineshaft.NONE : LayerSpec.Mineshaft.NORMAL;
			assertEquals(want, m, "mineshaft " + l);
		}
		// no frozen or reef layer holds the vanilla igloo / ocean ruin placeholder heights (y 50..90)
		for (int y = 50; y <= 90; y++) {
			int l = L.layerAt(y);
			if (l >= 1) {
				assertNotEquals(LayerTheme.FROZEN_DEPTHS, L.layer(l).theme());
				assertNotEquals(LayerTheme.SUNKEN_REEF, L.layer(l).theme());
			}
		}
	}

	@Test
	void oreCounts() {
		// main table (section 3.4): unlock layers 1, 3, 5, 8, 10, 12, 15
		List<LayerSpec.Ore> main = LayerTables.LAYOUT_2_ORES;
		int[] unlock = {1, 1, 3, 5, 8, 8, 10, 12, 12, 15};
		for (int i = 0; i < main.size(); i++) {
			assertEquals(0, main.get(i).count(unlock[i] - 1), "ore " + i + " before unlock");
			assertTrue(main.get(i).count(unlock[i]) > 0, "ore " + i + " at unlock");
		}
		assertEquals(6, main.getFirst().count(19)); // coal at the bottom
		assertEquals(11, L.layer(4).ores().size());
		assertEquals(4, L.layer(4).ores().get(10).count(4)); // bonus gold
		assertEquals(13, L.layer(18).ores().size());
		assertSame(main, L.layer(1).ores());
		assertEquals(2.0, L.layer(1).dungeonRate(), 1e-9);
		assertEquals(12.0, L.layer(19).dungeonRate(), 1e-9); // rooms per 20x300 strip, tuned (3.9)
		assertEquals(0.0, L.layer(7).dungeonRate());
	}
}
