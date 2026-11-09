package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** End layout 2 (the tall End, spec v2 section 5): tiers, gates both ways, the protected fight zone. */
class EndLayoutTest {
	private static final EndLayout E = EndLayout.LAYOUT_2;
	private static final int[] WIDTHS = {18, 20, 32, 48};

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void layoutOneIsTheOldEnd() {
		assertFalse(EndLayout.LAYOUT_1.tall);
		assertTrue(EndLayout.LAYOUT_1.tiers.isEmpty());
		assertEquals(0, EndLayout.LAYOUT_1.worldMinY);
		assertEquals(255, EndLayout.LAYOUT_1.worldMaxY);
		assertTrue(EndLayout.LAYOUT_1.gates(EndStripChunkGenerator.bounds(20)).isEmpty());
		assertEquals(EndLayout.LAYOUT_1, EndLayout.byId(1));
	}

	@Test
	void tiersMatchTheSpec() {
		assertEquals(-2032, E.worldMinY);
		assertEquals(2031, E.worldMaxY);
		int[] ascent = {384, 640, 896, 1152, 1408, 1664, 1920};
		int[] underside = {-384, -896, -1408, -1920};
		List<EndLayout.Tier> up = E.ascent(), down = E.underside();
		assertEquals(7, up.size());
		assertEquals(4, down.size());
		for (int i = 0; i < ascent.length; i++) assertEquals(ascent[i], up.get(i).y(), up.get(i).id());
		for (int i = 0; i < underside.length; i++) assertEquals(underside[i], down.get(i).y(), down.get(i).id());
		assertEquals("crown", up.get(6).id());
		// every tier's islands (+-32) stay inside the world and out of the vanilla band
		for (EndLayout.Tier t : E.tiers) {
			assertTrue(t.y() + EndLayout.ISLAND_RANGE < E.worldMaxY && t.y() - EndLayout.ISLAND_RANGE > E.worldMinY + 4, t.id());
			assertTrue(t.y() - 40 > EndLayout.BASE_MAX_Y || t.y() + 40 < EndLayout.BASE_MIN_Y, t.id());
			assertEquals(t, E.tierAt(t.y()), t.id());
			assertEquals(t, E.tierAt(t.y() + 30), t.id());
			assertEquals(t, E.tierAt(t.y() - 30), t.id());
		}
		assertNull(E.tierAt(0));
		assertNull(E.tierAt(255));
		assertEquals("crown", E.tierAt(EndLayout.CROWN_SPIRE_TOP_Y).id());
		assertEquals("crown", E.tierAt(EndLayout.CROWN_FOUNDATION_Y).id());
		assertEquals(E.tier("u4"), E.tierAt(-2027));
		assertEquals(E.tier("e1"), E.tierAt(256));
		assertEquals(E.tier("u1"), E.tierAt(-1));
	}

	@Test
	void zonesDoNotOverlap() {
		assertTrue(EndLayout.ZONE_MAX_Z < EndLayout.FIGHT_MIN_Z);
		assertTrue(EndLayout.inZone(EndLayout.END_A - 6) && EndLayout.inZone(EndLayout.END_B + 6));
		assertTrue(EndLayout.inZone(EndLayout.BASE_PLATFORM_Z));
		assertTrue(EndLayout.inZone(EndLayout.CROWN_MIN_Z - 8) && EndLayout.inZone(EndLayout.CROWN_MAX_Z + 8));
		assertTrue(EndStripChunkGenerator.MIN_Z <= EndLayout.ZONE_MIN_Z && EndStripChunkGenerator.MAX_Z >= EndLayout.FIGHT_MAX_Z);
		for (EndLayout.Tier t : E.tiers) {
			int[] zr = t.stalkerArena() ? E.stalkerArenaZ(t) : t.shape() == EndLayout.Shape.ARENA ? E.matriarchArenaZ(t) : null;
			if (zr != null) assertTrue(EndLayout.inZone(zr[0] - 4) && EndLayout.inZone(zr[1] + 4), t.id());
		}
	}

	/** Every gateway sends you onto a platform floor, and every tier can be reached and left both ways. */
	@Test
	void gatesLinkBothWays() {
		for (int w : WIDTHS) {
			StripBounds bounds = EndStripChunkGenerator.bounds(w);
			List<EndLayout.Gate> gates = E.gates(bounds);
			Set<BlockPos> positions = new HashSet<>();
			Map<String, EndLayout.Gate> byName = new HashMap<>();
			for (EndLayout.Gate g : gates) {
				assertTrue(positions.add(g.pos()), "two gates at " + g.pos());
				byName.put(g.name(), g);
				assertTrue(bounds.inside(g.pos().getX(), g.pos().getZ()) && bounds.inside(g.target().getX(), g.target().getZ()), g.name());
				assertTrue(EndLayout.inZone(g.pos().getZ()) && EndLayout.inZone(g.target().getZ()), g.name());
				// you never arrive on (or next to) a gateway
				for (EndLayout.Gate other : gates) {
					if (other.pos().getY() == g.target().getY()) assertTrue(other.pos().distManhattan(g.target()) >= 3, g.name() + " lands next to " + other.name());
				}
			}
			assertEquals(2 + 11 + 9, gates.size()); // ascent + descent, one Return Gate per tier, Rift Gates of E1..E6 and U1..U3
			// up: base -> E1 -> ... -> Crown, and back down
			assertEquals(E.startPlatform(E.tier("e1")).floorY() + 1, byName.get("ascent").target().getY());
			assertEquals(EndLayout.baseArrival(bounds), byName.get("e1_return").target());
			assertEquals(EndLayout.baseArrival(bounds), byName.get("u1_return").target());
			String[] up = {"e1", "e2", "e3", "e4", "e5", "e6", "crown"};
			for (int i = 0; i + 1 < up.length; i++) {
				EndLayout.Gate rift = byName.get(up[i] + "_rift"), back = byName.get(up[i + 1] + "_return");
				assertNotNull(rift, up[i]);
				assertEquals(E.tier(up[i + 1]).y() + 1, rift.target().getY(), up[i]);
				// the Return Gate of the next tier brings you back next to this Rift Gate
				assertEquals(rift.pos().getY(), back.target().getY());
				assertTrue(Math.abs(rift.pos().getZ() - back.target().getZ()) <= 4, up[i]);
				// tiers alternate ends
				assertTrue(Math.abs(rift.pos().getZ() - rift.target().getZ()) < 10, up[i]);
			}
			String[] down = {"u1", "u2", "u3", "u4"};
			for (int i = 0; i + 1 < down.length; i++) {
				EndLayout.Gate rift = byName.get(down[i] + "_rift");
				assertNotNull(rift, down[i]);
				assertEquals(E.tier(down[i + 1]).y() + 1, rift.target().getY());
				assertEquals(rift.pos().getY(), byName.get(down[i + 1] + "_return").target().getY());
			}
			assertNull(byName.get("crown_rift"));
			assertNull(byName.get("u4_rift"));
			// the U4 arrival lies inside the Matriarch's arena, the E5 Rift Gate inside the Stalker's
			int[] mz = E.matriarchArenaZ(E.tier("u4"));
			int arrive = byName.get("u3_rift").target().getZ();
			assertTrue(arrive > mz[0] && arrive < mz[1]);
			int[] sz = E.stalkerArenaZ(E.tier("e5"));
			int gate = byName.get("e5_rift").pos().getZ();
			assertTrue(gate > sz[0] && gate < sz[1]);
			BlockPos key = E.voidKeyChest(bounds, E.tier("u4"));
			assertTrue(key.getZ() > mz[0] && key.getZ() < mz[1]);
		}
	}

	@Test
	void crownFitsItsBand() {
		StripBounds bounds = EndStripChunkGenerator.bounds(18);
		EndLayout.Tier crown = E.tier("crown");
		assertEquals(EndLayout.CROWN_FLOOR_Y, crown.y());
		BlockPos start = EndLayout.arrival(bounds, E.startPlatform(crown));
		assertTrue(start.getZ() > EndLayout.CROWN_MIN_Z && start.getZ() < EndLayout.CROWN_SEAL_Z);
		assertTrue(EndLayout.hoardChest(bounds).getZ() < EndLayout.CROWN_SEAL_Z);
		assertTrue(EndLayout.vaultChest(bounds).getZ() > EndLayout.CROWN_SEAL_Z && EndLayout.vaultChest(bounds).getZ() < EndLayout.CROWN_MAX_Z);
		assertTrue(EndLayout.CROWN_FOUNDATION_Y >= 1888 && EndLayout.CROWN_SPIRE_TOP_Y <= 1984);
	}
}
