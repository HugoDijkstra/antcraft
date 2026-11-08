package dev.antcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** Pure geometry: strip bounds, floors and layers, holes, rooms. No Minecraft bootstrap needed. */
class StripGeometryTest {
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
		assertEquals(-64, Strip.floorBase(0));
		assertEquals(-62, Strip.floorTop(0));
		assertEquals(-1856, Strip.floorBase(7));
		// from the bottom up: bottom bedrock, layer 8, floor 7, layer 7, ..., floor 0, vanilla
		int expectedBottom = Strip.BOTTOM_TOP_Y + 1;
		for (int layer = Strip.DEEP_LAYERS; layer >= 1; layer--) {
			assertEquals(expectedBottom, Strip.layerBottom(layer), "layer " + layer + " bottom");
			int floorAbove = layer - 1;
			assertEquals(Strip.floorBase(floorAbove) - 1, Strip.layerTop(layer), "layer " + layer + " top");
			expectedBottom = Strip.floorTop(floorAbove) + 1;
		}
		assertEquals(Strip.floorTop(0) + 1, Strip.hallBottom(0));
		assertTrue(Strip.WORLD_MIN_Y < Strip.BOTTOM_TOP_Y);
	}

	@Test
	void layerAtAndFloorAtAgree() {
		for (int y = Strip.WORLD_MIN_Y; y <= Strip.WORLD_MAX_Y; y++) {
			int layer = Strip.layerAt(y), floor = Strip.floorAt(y);
			if (y > Strip.floorTop(0)) {
				assertEquals(0, layer, "y " + y);
				assertEquals(-1, floor, "y " + y);
			} else if (floor >= 0) {
				assertEquals(-1, layer, "floor y " + y);
				assertTrue(y >= Strip.floorBase(floor) && y <= Strip.floorTop(floor));
			} else if (y <= Strip.BOTTOM_TOP_Y) {
				assertEquals(-1, layer, "bottom y " + y);
			} else {
				assertTrue(layer >= 1 && layer <= Strip.DEEP_LAYERS, "y " + y);
				assertTrue(y >= Strip.layerBottom(layer) && y <= Strip.layerTop(layer));
			}
		}
		for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
			assertEquals(Strip.FLOOR_THICKNESS, Strip.floorTop(k) - Strip.floorBase(k) + 1);
		}
	}

	@Test
	void forestFitsInsideItsLayer() {
		for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
			int floor = Strip.forestFloor(layer);
			assertTrue(floor - DeepTerrain.LEAF_BAND_BELOW > Strip.layerBottom(layer) + Strip.HALL_HEIGHT, "layer " + layer);
			assertTrue(floor + DeepTerrain.LEAF_BAND_ABOVE < Strip.layerTop(layer), "layer " + layer);
		}
		assertTrue(Strip.CITY_MIN_Y >= Strip.layerBottom(Strip.CITY_LAYER) && Strip.CITY_MAX_Y <= Strip.layerTop(Strip.CITY_LAYER));
	}

	@Test
	void gateFloorsAreDistinct() {
		Set<Integer> gates = new HashSet<>();
		gates.add(Strip.SEALED_FLOOR);
		gates.add(Strip.ANCIENT_SEALED_FLOOR);
		for (int k : Strip.GUARDIAN_FLOORS) {
			assertTrue(Strip.isGuardianFloor(k));
			assertTrue(gates.add(k), "floor " + k + " has two gates");
		}
		assertFalse(Strip.isGuardianFloor(Strip.SEALED_FLOOR));
	}

	// ------------------------------------------------------------------ holes and rooms

	@Test
	void holesKeepTwoBlocksFromTheWalls() {
		for (StripBounds b : SIZES) {
			for (long seed : SEEDS) {
				for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
					int x = Strip.holeX(seed, k, b), z = Strip.holeZ(seed, k, b);
					assertTrue(x >= b.minX() + 2 && x <= b.maxX() - 2, b + " seed " + seed + " hole " + k + " x " + x);
					assertTrue(z >= b.minZ() + 2 && z <= b.maxZ() - 2, b + " seed " + seed + " hole " + k + " z " + z);
				}
			}
		}
	}

	@Test
	void holesAreDeterministic() {
		StripBounds b = StripBounds.of(20, 300);
		for (int k = 0; k < Strip.FLOOR_COUNT; k++) {
			assertEquals(Strip.holeX(12345L, k, b), Strip.holeX(12345L, k, b));
			assertEquals(Strip.holeZ(12345L, k, b), Strip.holeZ(12345L, k, b));
		}
		assertNotEquals(Strip.mix(1, 2), Strip.mix(2, 1));
	}

	@Test
	void lastHoleMissesThePortalRoom() {
		for (StripBounds b : SIZES) {
			for (long seed : SEEDS) {
				int room = Strip.portalRoomZ(seed, b);
				int z = Strip.holeZ(seed, Strip.FLOOR_COUNT - 1, b);
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
				int cz = Strip.portalRoomZ(seed, b);
				assertTrue(cz - PortalRoom.HALF_LENGTH >= b.minZ() + 20 && cz + PortalRoom.HALF_LENGTH - 1 <= b.maxZ() - 20, b + " room z " + cz);
				BlockPos target = Strip.portalTarget(seed, b);
				assertEquals(px, target.getX());
				assertEquals(Strip.portalRoomFloorY() + 2, target.getY());
				for (BlockPos p : List.of(PortalRoom.spawnerPos(seed, b), PortalRoom.chestA(seed, b), PortalRoom.chestB(seed, b))) {
					assertTrue(p.getX() > PortalRoom.minX(b) && p.getX() < PortalRoom.maxX(b), "inside the walls " + p);
					assertTrue(p.getZ() > cz - PortalRoom.HALF_LENGTH && p.getZ() < cz + PortalRoom.HALF_LENGTH - 1, "inside the walls " + p);
				}
			}
		}
		assertEquals(Strip.layerBottom(Strip.DEEP_LAYERS), Strip.portalRoomFloorY());
	}

	@Test
	void bossRoomDoorwaysAreInsideTheStrip() {
		for (StripBounds b : SIZES) {
			int doorways = 0;
			for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
				for (int y = Strip.floorTop(0) - 1; y <= Strip.floorTop(0) + Strip.HALL_HEIGHT; y++) {
					if (!ArenaManager.isDoorway(b, 0, x, y)) continue;
					doorways++;
					assertTrue(b.inside(x, b.minZ()));
					assertTrue(y > Strip.floorTop(0) && y <= Strip.floorTop(0) + Strip.HALL_HEIGHT);
				}
			}
			assertEquals((2 * ArenaManager.DOOR_HALF_WIDTH + 1) * ArenaManager.DOOR_HEIGHT, doorways);
			for (long seed : SEEDS) {
				for (int k : Strip.GUARDIAN_FLOORS) {
					assertEquals(2 * ArenaManager.WALL_OFFSET, ArenaManager.wallZ(seed, k, b, 1) - ArenaManager.wallZ(seed, k, b, -1));
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
				for (int layer = 1; layer <= Strip.DEEP_LAYERS; layer++) {
					ChunkPos chunk = Strip.villageChunk(seed, layer, b);
					assertTrue(chunk.z() > firstRow && chunk.z() < lastRow, b + " layer " + layer + " row " + chunk.z());
					assertTrue(b.chunkOverlaps(chunk));
					rows.add(chunk.z());
				}
				if (lastRow - firstRow - 1 >= Strip.DEEP_LAYERS) assertEquals(Strip.DEEP_LAYERS, rows.size(), b + " seed " + seed);
			}
		}
	}

	@Test
	void pyramidFitsInsideTheStrip() {
		for (StripBounds b : SIZES) {
			for (long seed : SEEDS) {
				ChunkPos chunk = Strip.pyramidChunk(seed, b);
				int north = (chunk.z() << 4) + Strip.PYRAMID_OFFSET_Z;
				assertTrue(north > b.minZ() && north + Strip.PYRAMID_SIZE - 1 < b.maxZ(), b + " seed " + seed + " north " + north);
			}
		}
	}

	@Test
	void cityStartsInTheMiddle() {
		StripBounds b = StripBounds.of(20, 300);
		assertEquals(new ChunkPos(0, 9), Strip.cityChunk(b));
	}
}
