package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Nether layout 3 (the tall Nether, spec v2 section 4): geometry, gates, towers and the layer table. */
class NetherLayoutTest {
	private static final NetherLayout N = NetherLayout.LAYOUT_3;
	private static final StripBounds[] BOUNDS = {StripBounds.of(20, 300), StripBounds.of(18, 150), StripBounds.of(32, 1000), StripBounds.of(48, 300)};

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void floorsAndLayersMatchTheSpec() {
		int[] bases = {1904, 1696, 1488, 1280, 1072, 864, 656, 448, 240, 32, -176, -384, -592, -800, -1008, -1216, -1424, -1632, -1840};
		assertEquals(19, N.floorCount);
		for (int k = 0; k < bases.length; k++) assertEquals(bases[k], N.floorBase(k), "NF" + k);
		assertEquals(1699, N.layerBottom(1));
		assertEquals(1903, N.layerTop(1));
		for (int l = 1; l <= 18; l++) assertEquals(205, N.layerTop(l) - N.layerBottom(l) + 1, "N" + l);
		assertEquals(-2027, N.layerBottom(19));
		assertEquals(-1841, N.layerTop(19));
		assertEquals(1907, N.hallBottom(0));
		assertEquals(-2010, N.lavaTop(19)); // the Ember Forge's lava sea
		assertEquals(N.layerBottom(10) + 60, N.lavaTop(10)); // the Lava Archipelago's lake
		assertEquals(0, N.layerAt(1904 + 3));
		assertEquals(-1, N.layerAt(1904));
		assertEquals(9, N.layerAt(100));
		assertEquals(12, N.layerAt(-559));
		assertEquals(5, N.layerAt(927));
		// the old layouts are not moved and have no deep layers
		assertFalse(NetherLayout.LAYOUT_1.tall());
		assertFalse(NetherLayout.LAYOUT_2.tall());
		assertEquals(0, NetherLayout.LAYOUT_2.layerAt(10));
	}

	@Test
	void guardiansAndThemes() {
		assertEquals(GuardianId.HOGLIN_WARLORD, N.guardian(2));
		assertEquals(GuardianId.MOLTEN_SOVEREIGN, N.guardian(7));
		assertEquals(GuardianId.BRUTE_WARLORD, N.guardian(12));
		assertEquals(GuardianId.BONE_COLOSSUS, N.guardian(17));
		assertEquals(4, N.guardianFloors().length);
		for (int k : N.guardianFloors()) assertNotNull(N.guardian(k).theme());
	}

	@Test
	void holesAndTowersKeepClearOfTheBossRooms() {
		for (StripBounds b : BOUNDS) {
			for (long seed = 1; seed <= 40; seed++) {
				for (int k = 0; k < N.floorCount; k++) {
					int hx = N.holeX(seed, k, b), hz = N.holeZ(seed, k, b);
					assertTrue(hx >= b.minX() + 2 && hx <= b.maxX() - 2 && hz >= b.minZ() + 2 && hz <= b.maxZ() - 2, "hole in strip");
				}
				for (int l = 1; l <= N.deepLayers; l++) {
					int[][] towers = N.towers(seed, l, b);
					assertEquals(N.holeX(seed, l - 1, b), towers[0][0], "tower under the hole above");
					for (int[] t : towers) {
						assertTrue(t[0] - 2 >= b.minX() && t[0] + 2 <= b.maxX() && t[1] - 2 >= b.minZ() && t[1] + 2 <= b.maxZ(), "tower inside the strip");
						if (l < N.deepLayers && N.isGuardianFloor(l)) {
							assertTrue(Math.abs(t[1] - N.holeZ(seed, l, b)) >= ArenaManager.WALL_OFFSET + 3, "tower outside the boss room (seed " + seed + ", N" + l + ")");
						} else if (l < N.deepLayers) {
							assertTrue(Math.abs(t[1] - N.holeZ(seed, l, b)) >= 4 || Math.abs(t[0] - N.holeX(seed, l, b)) >= 4, "tower clear of the hole");
						}
					}
				}
			}
		}
	}

	@Test
	void rowsAreDistinct() {
		Set<String> combos = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (int l = 1; l <= N.deepLayers; l++) {
			NetherRow row = N.row(l);
			assertTrue(combos.add(row.base() + "/" + row.patch() + "/" + row.shape()), "N" + l + " repeats another layer's blocks and shape");
			assertTrue(names.add(row.englishName()));
		}
		assertTrue(N.row(5).mobs() != null && N.row(5).mobs().isEmpty(), "the Warped Sanctuary has no natural monsters");
		assertEquals(4, N.row(15).quartz(), 1e-9);
		assertEquals(3, N.row(11).gold(), 1e-9);
	}

	@Test
	void anchorShrineAndForgeHeights() {
		StripBounds b = BOUNDS[0];
		assertEquals(927, N.anchorShrine(42, b).getY());
		assertEquals(-2005, N.emberForge(42, b).getY() - 1); // plinth top
	}
}
