package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** Pure geometry: strip bounds, floors and layers, holes, rooms. No Minecraft bootstrap needed (see {@link LayoutTablesTest} for the block tables). */
class StripGeometryTest {
	private static final StripLayout L = StripLayout.LAYOUT_1;
	private static final long[] SEEDS = {0L, 1L, 12345L, 777L, -42L, Long.MAX_VALUE, Long.MIN_VALUE, 0x5EEDL};
	private static final List<StripBounds> SIZES = List.of(
		StripBounds.of(StripBounds.DEFAULT_WIDTH, StripBounds.DEFAULT_LENGTH),
		StripBounds.of(StripBounds.MIN_WIDTH, StripBounds.MIN_LENGTH),
		StripBounds.of(StripBounds.MAX_WIDTH, StripBounds.MAX_LENGTH),
		StripBounds.of(19, 151),
		StripBounds.of(33, 600)
	);

	// ------------------------------------------------------------------ StripBounds

	@Test
	void defaultBounds() {
		StripBounds b = StripBounds.of(20, 300);
		assertEquals(new StripBounds(0, 19, 0, 299), b);
		assertEquals(20, b.width());
		assertEquals(300, b.length());
		assertEquals(10, b.centerX());
		assertEquals(150, b.centerZ());
		assertEquals(-1, b.wallMinX());
		assertEquals(20, b.wallMaxX());
		assertEquals(-1, b.wallMinZ());
		assertEquals(300, b.wallMaxZ());
	}

	@Test
	void emptyBoundsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new StripBounds(5, 4, 0, 10));
		assertThrows(IllegalArgumentException.class, () -> new StripBounds(0, 10, 5, 4));
	}

	@Test
	void insideAndWallsPartitionTheRing() {
		for (StripBounds b : SIZES) {
			for (int x = b.wallMinX() - 2; x <= b.wallMaxX() + 2; x++) {
				for (int z = b.wallMinZ() - 2; z <= b.wallMaxZ() + 2; z++) {
					boolean inside = x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ();
					boolean ring = !inside && x >= b.minX() - 1 && x <= b.maxX() + 1 && z >= b.minZ() - 1 && z <= b.maxZ() + 1;
					assertEquals(inside, b.inside(x, z), b + " inside " + x + "," + z);
					assertEquals(ring, b.isWall(x, z), b + " wall " + x + "," + z);
				}
			}
		}
	}

	@Test
	void chunkOverlapAndTouch() {
		StripBounds b = StripBounds.of(20, 300);
		assertTrue(b.chunkOverlaps(new ChunkPos(0, 0)));
		assertTrue(b.chunkOverlaps(new ChunkPos(1, 18)));
		assertFalse(b.chunkOverlaps(new ChunkPos(-1, 0)), "chunk -1 only holds the wall");
		assertTrue(b.chunkTouches(new ChunkPos(-1, 0)));
		assertTrue(b.chunkTouches(new ChunkPos(0, -1)));
		assertFalse(b.chunkTouches(new ChunkPos(-2, 0)));
		assertFalse(b.chunkTouches(new ChunkPos(2, 0)));
		assertFalse(b.chunkOverlaps(new ChunkPos(0, 19)), "z 304..319 lies beyond z 299");
		assertFalse(b.chunkTouches(new ChunkPos(0, 19)), "the wall at z 300 is in chunk 18");
		for (StripBounds s : SIZES) {
			for (int cx = -4; cx <= 4; cx++) {
				for (int cz = -2; cz <= 70; cz++) {
					ChunkPos pos = new ChunkPos(cx, cz);
					if (s.chunkOverlaps(pos)) assertTrue(s.chunkTouches(pos));
				}
			}
		}
	}

	@Test
	void clampInsideKeepsMargin() {
		for (StripBounds b : SIZES) {
			for (int margin = 0; margin <= 3; margin++) {
				for (BlockPos p : List.of(new BlockPos(-50, 64, -50), new BlockPos(5000, 1, 5000), new BlockPos(b.centerX(), 0, b.centerZ()))) {
					BlockPos c = b.clampInside(p, margin);
					assertTrue(b.inside(c.getX(), c.getZ()));
					assertTrue(c.getX() - b.minX() >= margin && b.maxX() - c.getX() >= margin);
					assertTrue(c.getZ() - b.minZ() >= margin && b.maxZ() - c.getZ() >= margin);
					assertEquals(p.getY(), c.getY());
				}
			}
		}
	}

	@Test
	void scaledZ() {
		StripBounds b = StripBounds.of(20, 300);
		assertEquals(0, b.scaledZ(0));
		assertEquals(120, b.scaledZ(120));
		StripBounds longer = StripBounds.of(20, 600);
		assertEquals(240, longer.scaledZ(120));
		for (StripBounds s : SIZES) {
			for (int z = 0; z < StripBounds.DEFAULT_LENGTH; z++) assertTrue(s.inside(s.minX(), s.scaledZ(z)));
		}
	}

	// ------------------------------------------------------------------ floors and layers

	@Test
	void floorsAndLayersStackWithoutGaps() {
		assertEquals(-64, L.floorBase(0));
		assertEquals(-62, L.floorTop(0));
		assertEquals(-1856, L.floorBase(7));
		// from the bottom up: bottom bedrock, layer 8, floor 7, layer 7, ..., floor 0, vanilla
		int expectedBottom = L.bottomTopY + 1;
		for (int layer = L.deepLayers; layer >= 1; layer--) {
			assertEquals(expectedBottom, L.layerBottom(layer), "layer " + layer + " bottom");
			int floorAbove = layer - 1;
			assertEquals(L.floorBase(floorAbove) - 1, L.layerTop(layer), "layer " + layer + " top");
			expectedBottom = L.floorTop(floorAbove) + 1;
		}
		assertEquals(L.floorTop(0) + 1, L.hallBottom(0));
		assertTrue(L.worldMinY < L.bottomTopY);
	}

	@Test
	void layerAtAndFloorAtAgree() {
		for (int y = L.worldMinY; y <= L.worldMaxY; y++) {
			int layer = L.layerAt(y), floor = L.floorAt(y);
			if (y > L.floorTop(0)) {
				assertEquals(0, layer, "y " + y);
				assertEquals(-1, floor, "y " + y);
			} else if (floor >= 0) {
				assertEquals(-1, layer, "floor y " + y);
				assertTrue(y >= L.floorBase(floor) && y <= L.floorTop(floor));
			} else if (y <= L.bottomTopY) {
				assertEquals(-1, layer, "bottom y " + y);
			} else {
				assertTrue(layer >= 1 && layer <= L.deepLayers, "y " + y);
				assertTrue(y >= L.layerBottom(layer) && y <= L.layerTop(layer));
			}
		}
		for (int k = 0; k < L.floorCount; k++) {
			assertEquals(L.floorThickness, L.floorTop(k) - L.floorBase(k) + 1);
		}
	}

	@Test
	void forestFitsInsideItsLayer() {
		for (int layer = 1; layer <= L.deepLayers; layer++) {
			int floor = L.forestFloor(layer);
			assertTrue(floor - DeepTerrain.LEAF_BAND_BELOW > L.layerBottom(layer) + L.hallHeight, "layer " + layer);
			assertTrue(floor + (L.forestHeight + DeepTerrain.LEAF_BAND_OVER_FOREST) < L.layerTop(layer), "layer " + layer);
		}
		assertTrue(L.cityMinY() >= L.layerBottom(L.cityLayer) && L.cityMaxY() <= L.layerTop(L.cityLayer));
	}

	@Test
	void gateFloorsAreDistinct() {
		Set<Integer> gates = new HashSet<>();
		gates.add(L.sealedFloor);
		gates.add(L.ancientSealedFloor);
		for (int k : L.guardianFloors()) {
			assertTrue(L.isGuardianFloor(k));
			assertTrue(gates.add(k), "floor " + k + " has two gates");
		}
		assertFalse(L.isGuardianFloor(L.sealedFloor));
	}

	// ------------------------------------------------------------------ holes and rooms

	@Test
	void holesKeepTwoBlocksFromTheWalls() {
		for (StripBounds b : SIZES) {
			for (long seed : SEEDS) {
				for (int k = 0; k < L.floorCount; k++) {
					int x = L.holeX(seed, k, b), z = L.holeZ(seed, k, b);
					assertTrue(x >= b.minX() + 2 && x <= b.maxX() - 2, b + " seed " + seed + " hole " + k + " x " + x);
					assertTrue(z >= b.minZ() + 2 && z <= b.maxZ() - 2, b + " seed " + seed + " hole " + k + " z " + z);
				}
			}
		}
	}

	@Test
	void holesAreDeterministic() {
		StripBounds b = StripBounds.of(20, 300);
		for (int k = 0; k < L.floorCount; k++) {
			assertEquals(L.holeX(12345L, k, b), L.holeX(12345L, k, b));
			assertEquals(L.holeZ(12345L, k, b), L.holeZ(12345L, k, b));
		}
		assertNotEquals(Strip.mix(1, 2), Strip.mix(2, 1));
	}

	@Test
	void lastHoleMissesThePortalRoom() {
		for (StripBounds b : SIZES) {
			for (long seed : SEEDS) {
				int room = L.portalRoomZ(seed, b);
				int z = L.holeZ(seed, L.floorCount - 1, b);
				assertTrue(Math.abs(z - room) > PortalRoom.HALF_LENGTH + 3, b + " seed " + seed + " hole z " + z + " room z " + room);
			}
		}
	}

	@Test
	void portalRoomFitsInsideTheStrip() {
		for (StripBounds b : SIZES) {
			assertTrue(PortalRoom.minX(b) >= b.minX() && PortalRoom.maxX(b) <= b.maxX(), b.toString());
			assertEquals(PortalRoom.WIDTH, PortalRoom.maxX(b) - PortalRoom.minX(b) + 1);
			int px = PortalRoom.portalX(b);
			assertTrue(px - 3 > PortalRoom.minX(b) && px + 3 < PortalRoom.maxX(b), "platform inside the room for " + b);
			for (long seed : SEEDS) {
				int cz = L.portalRoomZ(seed, b);
				assertTrue(cz - PortalRoom.HALF_LENGTH >= b.minZ() + 20 && cz + PortalRoom.HALF_LENGTH - 1 <= b.maxZ() - 20, b + " room z " + cz);
				BlockPos target = L.portalTarget(seed, b);
				assertEquals(px, target.getX());
				assertEquals(L.portalRoomFloorY() + 2, target.getY());
				for (BlockPos p : List.of(PortalRoom.spawnerPos(seed, b, L), PortalRoom.chestA(seed, b, L), PortalRoom.chestB(seed, b, L))) {
					assertTrue(p.getX() > PortalRoom.minX(b) && p.getX() < PortalRoom.maxX(b), "inside the walls " + p);
					assertTrue(p.getZ() > cz - PortalRoom.HALF_LENGTH && p.getZ() < cz + PortalRoom.HALF_LENGTH - 1, "inside the walls " + p);
				}
			}
		}
		assertEquals(L.layerBottom(L.deepLayers), L.portalRoomFloorY());
	}

	@Test
	void bossRoomDoorwaysAreInsideTheStrip() {
		for (StripBounds b : SIZES) {
			int doorways = 0;
			for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
				for (int y = L.floorTop(0) - 1; y <= L.floorTop(0) + L.hallHeight; y++) {
					if (!ArenaManager.isDoorway(L, b, 0, x, y)) continue;
					doorways++;
					assertTrue(b.inside(x, b.minZ()));
					assertTrue(y > L.floorTop(0) && y <= L.floorTop(0) + L.hallHeight);
				}
			}
			assertEquals((2 * ArenaManager.DOOR_HALF_WIDTH + 1) * ArenaManager.DOOR_HEIGHT, doorways);
			for (long seed : SEEDS) {
				for (int k : L.guardianFloors()) {
					assertEquals(2 * ArenaManager.WALL_OFFSET, ArenaManager.wallZ(L, seed, k, b, 1) - ArenaManager.wallZ(L, seed, k, b, -1));
				}
			}
		}
	}

	@Test
	void keyShrineFitsInsideTheStrip() {
		for (StripBounds b : SIZES) {
			for (int layout = 1; layout <= 2; layout++) {
				int minX = KeyShrine.minX(b), minZ = KeyShrine.minZ(b, layout);
				assertTrue(minX >= b.minX() && minX + KeyShrine.SIZE - 1 <= b.maxX(), b + " layout " + layout);
				assertTrue(minZ >= b.minZ() && minZ + KeyShrine.SIZE - 1 <= b.maxZ(), b + " layout " + layout);
				BlockPos chest = KeyShrine.chestPos(b, layout);
				assertTrue(b.inside(chest.getX(), chest.getZ()));
			}
		}
	}

	// ------------------------------------------------------------------ villages and the pyramid

	@Test
	void villagesGetDistinctRowsInsideTheStrip() {
		for (StripBounds b : SIZES) {
			int firstRow = b.minZ() >> 4, lastRow = b.maxZ() >> 4;
			for (long seed : SEEDS) {
				Set<Integer> rows = new HashSet<>();
				for (int layer = 1; layer <= L.deepLayers; layer++) {
					ChunkPos chunk = L.villageChunk(seed, layer, b);
					assertTrue(chunk.z() > firstRow && chunk.z() < lastRow, b + " layer " + layer + " row " + chunk.z());
					assertTrue(b.chunkOverlaps(chunk));
					rows.add(chunk.z());
				}
				if (lastRow - firstRow - 1 >= L.deepLayers) assertEquals(L.deepLayers, rows.size(), b + " seed " + seed);
			}
		}
	}

	@Test
	void pyramidFitsInsideTheStrip() {
		for (StripBounds b : SIZES) {
			for (long seed : SEEDS) {
				ChunkPos chunk = L.pyramidChunk(seed, b);
				int north = (chunk.z() << 4) + StripLayout.PYRAMID_OFFSET_Z;
				assertTrue(north > b.minZ() && north + StripLayout.PYRAMID_SIZE - 1 < b.maxZ(), b + " seed " + seed + " north " + north);
			}
		}
	}

	@Test
	void cityStartsInTheMiddle() {
		StripBounds b = StripBounds.of(20, 300);
		assertEquals(new ChunkPos(0, 9), L.cityChunk(b));
	}

	// ------------------------------------------------------------------ layout 1 == the frozen original geometry

	/** 20 seeds, 4 strip sizes (as in the spec's golden check). */
	private static final long[] GOLDEN_SEEDS = {
		0L, 1L, 2L, 3L, 12345L, 777L, 424242L, -1L, -42L, 99L, 1L << 40, 0x5EEDL, 0xDEADBEEFL, -0x1234567L, 31337L, 8675309L, 42L, 2024L,
		Long.MAX_VALUE, Long.MIN_VALUE,
	};
	private static final List<StripBounds> GOLDEN_SIZES = List.of(
		StripBounds.of(StripBounds.DEFAULT_WIDTH, StripBounds.DEFAULT_LENGTH),
		StripBounds.of(StripBounds.MIN_WIDTH, StripBounds.MIN_LENGTH),
		StripBounds.of(StripBounds.MAX_WIDTH, StripBounds.MAX_LENGTH),
		StripBounds.of(33, 600)
	);

	@Test
	void layout1ConstantsEqualTheOriginal() {
		assertEquals(1, L.id);
		assertEquals(0, L.frameOffset);
		assertEquals(LegacyGeometry.WORLD_MIN_Y, L.worldMinY);
		assertEquals(LegacyGeometry.WORLD_MAX_Y, L.worldMaxY);
		assertEquals(LegacyGeometry.VANILLA_MIN_Y, L.vanillaMinY);
		assertEquals(LegacyGeometry.BOTTOM_TOP_Y, L.bottomTopY);
		assertEquals(LegacyGeometry.FLOOR_COUNT, L.floorCount);
		assertEquals(LegacyGeometry.DEEP_LAYERS, L.deepLayers);
		assertEquals(LegacyGeometry.FLOOR_THICKNESS, L.floorThickness);
		assertEquals(LegacyGeometry.HALL_HEIGHT, L.hallHeight);
		assertEquals(LegacyGeometry.FOREST_HEIGHT, L.forestHeight);
		assertEquals(LegacyGeometry.CITY_LAYER, L.cityLayer);
		assertEquals(LegacyGeometry.CITY_START_Y, L.cityStartY);
		assertEquals(0, L.cityAnchorDelta);
		assertEquals(LegacyGeometry.CITY_MIN_Y, L.cityMinY());
		assertEquals(LegacyGeometry.CITY_MAX_Y, L.cityMaxY());
		assertEquals(LegacyGeometry.SEALED_FLOOR, L.sealedFloor);
		assertEquals(LegacyGeometry.ANCIENT_SEALED_FLOOR, L.ancientSealedFloor);
		assertEquals(-1, L.emberSealedFloor);
		assertEquals(-1, L.vaultLayer);
		assertEquals(LegacyGeometry.DESERT_LAYER, L.desertLayer);
		assertEquals(LegacyGeometry.PYRAMID_SIZE, StripLayout.PYRAMID_SIZE);
		assertEquals(LegacyGeometry.PYRAMID_OFFSET_Z, StripLayout.PYRAMID_OFFSET_Z);
		assertArrayEquals(LegacyGeometry.GUARDIAN_FLOORS, L.guardianFloors());
		for (int l = 1; l < L.floorCount; l++) assertEquals(LegacyGeometry.FLOOR_SPACING, L.spacing(l));
		assertEquals(GuardianId.TUNNEL_CAPTAIN, L.guardian(0));
		assertEquals(GuardianId.DEEP_BEAST, L.guardian(2));
		assertEquals(GuardianId.DESERT_COLOSSUS, L.guardian(6));
		assertSame(L, StripLayout.byId(1));
	}

	@Test
	void layout1GeometryEqualsTheOriginal() {
		for (int k = 0; k <= 7; k++) {
			assertEquals(LegacyGeometry.floorBase(k), L.floorBase(k), "floorBase " + k);
			assertEquals(LegacyGeometry.floorTop(k), L.floorTop(k), "floorTop " + k);
			assertEquals(LegacyGeometry.isGuardianFloor(k), L.isGuardianFloor(k), "guardian " + k);
		}
		for (int layer = 0; layer <= 8; layer++) {
			assertEquals(LegacyGeometry.hallBottom(layer), L.hallBottom(layer), "hallBottom " + layer);
			if (layer >= 1) {
				assertEquals(LegacyGeometry.layerBottom(layer), L.layerBottom(layer), "layerBottom " + layer);
				assertEquals(LegacyGeometry.layerTop(layer), L.layerTop(layer), "layerTop " + layer);
				assertEquals(LegacyGeometry.forestFloor(layer), L.forestFloor(layer), "forestFloor " + layer);
				assertEquals(LegacyGeometry.villageType(layer), L.villageType(layer), "villageType " + layer);
				assertEquals("deep_village_" + LegacyGeometry.villageType(layer), L.villageStructure(layer), "villageStructure " + layer);
				assertEquals((float) layer, L.hardnessScale(layer), "hardnessScale " + layer);
				assertEquals(layer, L.scaledLayer(layer), "scaledLayer " + layer);
				for (double perLayer : new double[] {0.0, 0.25, 0.5, 1.0, 2.0}) {
					assertEquals(LegacyGeometry.hardness(layer, perLayer), 1.0F + (float) perLayer * L.hardnessScale(layer), "hardness " + layer);
				}
			}
		}
		for (int y = L.worldMinY - 5; y <= L.worldMaxY + 5; y++) {
			assertEquals(LegacyGeometry.layerAt(y), L.layerAt(y), "layerAt " + y);
			assertEquals(LegacyGeometry.floorAt(y), L.floorAt(y), "floorAt " + y);
		}
		assertEquals(LegacyGeometry.portalRoomFloorY(), L.portalRoomFloorY());
		for (int rows = 1; rows <= 200; rows++) assertEquals(LegacyGeometry.villageStep(rows), L.villageStep(rows), "villageStep " + rows);
		for (StripBounds b : GOLDEN_SIZES) {
			assertEquals(LegacyGeometry.cityChunk(b), L.cityChunk(b));
			for (long seed : GOLDEN_SEEDS) {
				String where = b + " seed " + seed;
				for (int k = 0; k <= 7; k++) {
					assertEquals(LegacyGeometry.holeX(seed, k, b), L.holeX(seed, k, b), where + " holeX " + k);
					assertEquals(LegacyGeometry.holeZ(seed, k, b), L.holeZ(seed, k, b), where + " holeZ " + k);
				}
				for (int layer = 1; layer <= 8; layer++) {
					assertEquals(LegacyGeometry.villageChunk(seed, layer, b), L.villageChunk(seed, layer, b), where + " villageChunk " + layer);
				}
				assertEquals(LegacyGeometry.pyramidChunk(seed, b), L.pyramidChunk(seed, b), where + " pyramid");
				assertEquals(LegacyGeometry.portalRoomZ(seed, b), L.portalRoomZ(seed, b), where + " portal room");
				assertEquals(LegacyGeometry.portalTarget(seed, b), L.portalTarget(seed, b), where + " portal target");
			}
		}
	}

	@Test
	void unknownLayoutsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> StripLayout.byId(0));
		assertThrows(IllegalArgumentException.class, () -> StripLayout.byId(StripLayout.LATEST + 1));
	}
}
