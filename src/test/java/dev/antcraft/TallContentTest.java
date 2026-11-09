package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Layout 2 content (spec v2 phase 3): depth scaling, starts, guardians, mineshaft bands. */
class TallContentTest {
	private static final StripLayout L = StripLayout.LAYOUT_2;
	private static final List<StripBounds> BOUNDS = List.of(StripBounds.of(20, 300), StripBounds.of(18, 150), StripBounds.of(32, 1000), StripBounds.of(48, 300));
	private static final long[] SEEDS = {12345L, 777L, 424242L, -1L, 0L, 99L, 31337L, 5L};

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void scaledLayer() {
		for (int l = 1; l <= 8; l++) assertEquals(l, StripLayout.LAYOUT_1.scaledLayer(l));
		int[] want = {0, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 5, 6, 6, 7, 7, 8, 8};
		for (int l = 1; l <= 19; l++) assertEquals(want[l], L.scaledLayer(l), "layer " + l);
		assertEquals(8.0f, L.hardnessScale(19), 1e-6);
		// villager levels and zombie gear stay in range at the bottom
		assertEquals(5, Math.min(5, 1 + (L.scaledLayer(19) + 1) / 2));
		assertEquals(3, (19 - 1) * 4 / L.deepLayers);
	}

	@Test
	void oreCountsPerLayer() {
		// coal 24 - (L-1), copper 10, iron from 3, gold from 5, ... (spec 3.4 table)
		for (int l = 1; l <= 19; l++) {
			List<LayerSpec.Ore> ores = L.layer(l).ores();
			assertEquals(24 - (l - 1), ores.get(0).count(l), "coal " + l);
			assertEquals(10, ores.get(1).count(l));
			assertEquals(l >= 3 ? 9 : 0, ores.get(2).count(l));
			assertEquals(l >= 5 ? 6 : 0, ores.get(3).count(l));
			assertEquals(l >= 8 ? 5 : 0, ores.get(4).count(l));
			assertEquals(l >= 10 ? 3 : 0, ores.get(6).count(l));
			assertEquals(l >= 15 ? 1 : 0, ores.get(9).count(l));
			assertTrue(ores.get(0).count(l) > 0);
		}
	}

	@Test
	void villagesAndStartsGetDistinctRows() {
		for (long seed : SEEDS) {
			for (StripBounds b : BOUNDS) {
				// one structure key per layer, so layers may share a chunk; long enough strips still spread them out
				Set<ChunkPos> villages = new HashSet<>();
				Set<String> keys = new HashSet<>();
				int villageRows = (b.maxZ() >> 4) - (b.minZ() >> 4) - 1;
				for (int l = 1; l <= L.deepLayers; l++) {
					if (L.villageType(l) == null) continue;
					ChunkPos c = L.villageChunk(seed, l, b);
					assertTrue(keys.add(L.villageStructure(l)), "village key shared, layer " + l);
					if (villageRows >= 17) assertTrue(villages.add(c), "village chunk shared, layer " + l + " seed " + seed + " " + b);
					assertTrue(c.z() > b.minZ() >> 4 && c.z() < b.maxZ() >> 4);
				}
				// the outpost keeps six rows from the Darkwood village where the strip allows it
				ChunkPos outpost = DeepStarts.projectedChunk(seed, b, L, 5, 8900);
				int rows = (b.maxZ() >> 4) - (b.minZ() >> 4) - 1;
				if (rows >= 13) assertTrue(Math.abs(outpost.z() - L.villageChunk(seed, 5, b).z()) >= 6, "outpost near village, seed " + seed + " " + b);
				// the vault's start lies inside the strip, its z within the middle half
				int vz = DeepStarts.vaultZ(seed, b);
				assertTrue(vz >= b.minZ() + b.length() / 4 - 2 && vz <= b.maxZ() - b.length() / 4 + 2, "vault z " + vz + " " + b);
				for (int l = 1; l <= L.deepLayers; l++) {
					ChunkPos m = DeepStarts.mineshaftChunk(seed, b, l);
					assertTrue(m.z() >= (b.minZ() >> 4) + 2 && m.z() <= (b.maxZ() >> 4), "mineshaft row " + l);
				}
			}
		}
	}

	@Test
	void lastHoleAvoidsThePortalRoom() {
		for (long seed : SEEDS) {
			for (StripBounds b : BOUNDS) {
				int hz = L.holeZ(seed, L.floorCount - 1, b), room = L.portalRoomZ(seed, b);
				assertTrue(Math.abs(hz - room) > PortalRoom.HALF_LENGTH + 3, "floor 18 hole over the portal room, seed " + seed + " " + b);
			}
		}
	}

	@Test
	void mineshaftBands() {
		int[] layers = {1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 13, 14, 15, 16, 17, 18};
		int[] roomY = {1479, 1283, 1087, 891, 695, 499, 175, -21, -217, -413, -805, -1001, -1197, -1393, -1589, -1785};
		for (int i = 0; i < layers.length; i++) {
			int l = layers[i];
			assertNotEquals(LayerSpec.Mineshaft.NONE, L.layer(l).mineshaft());
			assertEquals(roomY[i], L.layerBottom(l) + 40, "room y " + l);
			int[] band = DeepStarts.mineshaftBand(L, l);
			assertEquals(L.layerBottom(l) + 12, band[0]);
			assertEquals(l == 2 ? 1296 : L.layerBottom(l) + 72, band[1], "band top " + l);
			// the forest (and its flattening test) stays above the band
			assertTrue(band[1] < L.forestFloor(l) - 12);
		}
	}

	@Test
	void guardiansAndThemes() {
		int[] floors = L.guardianFloors();
		assertEquals(7, floors.length);
		Set<GuardianId.Theme> themes = new HashSet<>();
		for (int k : floors) assertTrue(themes.add(L.guardian(k).theme()), "theme of floor " + k);
		assertTrue(GuardianId.BROOD_MOTHER.theme().extraCobwebs());
		assertTrue(GuardianId.DESERT_COLOSSUS.theme().candles());
		// every scaled guardian fits the 6-tall hall
		for (int k : floors) assertTrue(L.guardian(k).scale * 2.0 < L.hallHeight, "guardian " + k);
		assertEquals(16, L.emberSealedFloor);
		assertEquals(-1, StripLayout.LAYOUT_1.emberSealedFloor);
	}

	@Test
	void distinctRows() {
		// spec 3.2: no two rows share a stone pair, forest biome or non-empty tree list; all have a theme
		Set<String> pairs = new HashSet<>();
		Set<Object> biomes = new HashSet<>(), trees = new HashSet<>(), themes = new HashSet<>();
		for (int l = 1; l <= 19; l++) {
			LayerSpec row = L.layer(l);
			assertTrue(pairs.add(row.baseStone() + "/" + row.patchStone()));
			assertTrue(biomes.add(row.forestBiome()));
			if (!row.trees().isEmpty()) assertTrue(trees.add(row.trees()));
			assertTrue(themes.add(row.theme()), "theme twice: " + row.theme());
		}
	}

	@Test
	void plannedMonsterRoomsAddUpPerLayer() {
		for (StripBounds b : BOUNDS) {
			List<ChunkPos> chunks = ThemeDecor.roomChunks(b);
			assertTrue(!chunks.isEmpty(), "room chunks in " + b);
			for (long seed : SEEDS) {
				for (int layer = 1; layer <= L.deepLayers; layer++) {
					LayerSpec spec = L.layer(layer);
					int total = 0;
					for (ChunkPos pos : chunks) total += ThemeDecor.plannedRooms(seed, b, spec, layer, pos, chunks);
					int want = (int) Math.round(spec.dungeonRate() * b.width() * b.length() / 6000.0);
					assertEquals(want, total, "layer " + layer + " seed " + seed + " " + b);
					if (layer == L.vaultLayer) assertEquals(0, total);
				}
			}
		}
		// the default strip: 2 rooms at the top, 12 at the bottom, non-decreasing with depth (the vault has none)
		StripBounds d = StripBounds.of(20, 300);
		List<ChunkPos> chunks = ThemeDecor.roomChunks(d);
		int last = 0;
		for (int layer = 1; layer <= L.deepLayers; layer++) {
			if (layer == L.vaultLayer) continue;
			int total = 0;
			for (ChunkPos pos : chunks) total += ThemeDecor.plannedRooms(12345L, d, L.layer(layer), layer, pos, chunks);
			assertTrue(total >= last, "layer " + layer);
			last = total;
		}
		assertEquals(12, last);
	}

	@Test
	void vaultExitHoleStaysInsideItsChunk() {
		assertEquals(17, VaultPlan.inChunkNearest(16, 10, 30)); // 16 is a chunk edge
		assertEquals(14, VaultPlan.inChunkNearest(15, 10, 15));
		assertEquals(15, VaultPlan.inChunkNearest(15, 15, 16)); // nothing inside: plain clamp
		assertEquals(5, VaultPlan.inChunkNearest(5, 3, 9));
		assertEquals(-2, VaultPlan.inChunkNearest(-1, -5, 5)); // -1 and 0 are chunk edges; ties go low
	}
}
