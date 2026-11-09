package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * A nether-brick shrine in the Nether strip that holds the Nether Key, guarded by two blaze
 * spawners. Corridors run along the strip from both doors so it can be reached from either side.
 */
public final class KeyShrine {
	private KeyShrine() {}

	static final int FLOOR_Y = 70, CEIL_Y = 76;
	static final int CORRIDOR = 14;
	static final int SIZE = 10;

	/** West wall x: the shrine is centred in the strip's width (x 5..14 for a 20-wide strip). */
	static int minX(StripBounds bounds) {
		return bounds.centerX() - SIZE / 2;
	}

	/**
	 * North wall z: two fifths of the way along the strip (z 120..129 for 300 long), between the
	 * bastion and the fortress; in Nether layout 1 a sixth of the way (z 50..59), 2 blocks into a chunk.
	 */
	static int minZ(StripBounds bounds, int layout) {
		return layout >= 2 ? bounds.scaledZ(120) : ((bounds.scaledZ(50) >> 4) << 4) + 2;
	}

	public static BlockPos chestPos(StripBounds bounds, int layout) {
		return new BlockPos(minX(bounds) + 4, floorY(layout) + 2, minZ(bounds, layout) + 4);
	}

	/** Shrine floor: y 70, moved with the vanilla Nether in the tall layout (1974). */
	static int floorY(int layout) {
		return FLOOR_Y + NetherLayout.byId(layout).frameOffset;
	}

	/** Tall layout: centre of the stair shaft down to the NF0 tunnel, just past the north corridor's end. */
	static BlockPos shaftCentre(StripBounds bounds, int layout) {
		int midX = minX(bounds) + SIZE / 2 - 1;
		return new BlockPos(midX, floorY(layout), minZ(bounds, layout) - CORRIDOR - 3);
	}

	/**
	 * The stair shaft from the shrine's north corridor down to the NF0 tunnel hall (tall layout): a
	 * 5x5 nether-brick tower with spiral stairs, open into the corridor at the top and into the
	 * tunnel at the bottom. Rebuilt like the shrine (whatever the fortress or features put there).
	 */
	private static void stairShaft(WorldGenLevel level, StripBounds bounds, int layout, int regionMinX, int regionMaxX, int regionMinZ, int regionMaxZ) {
		NetherLayout nether = NetherLayout.byId(layout);
		BlockPos c = shaftCentre(bounds, layout);
		int hall = nether.hallBottom(0), floorY = floorY(layout);
		// the top step is the corridor floor, at ring index 5 (dz = +1, next to the doorway); the steps repeat every 8 blocks
		int bottom = hall - 8 + Math.floorMod(floorY - hall - 5, 8);
		DeepNetherTerrain.Tower tower = new DeepNetherTerrain.Tower(c.getX(), c.getZ(), bottom, floorY + 1, Integer.MIN_VALUE, false);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		BlockState bricks = Blocks.NETHER_BRICKS.defaultBlockState();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int x = c.getX() + dx, z = c.getZ() + dz;
				if (x < regionMinX || x > regionMaxX || z < regionMinZ || z > regionMaxZ || !bounds.inside(x, z)) continue;
				for (int y = hall; y <= floorY + 4; y++) {
					BlockState want;
					if (y <= floorY) {
						want = DeepNetherTerrain.towerBlock(tower, dx, y, dz, bricks);
						boolean perimeter = Math.abs(dx) == 2 || Math.abs(dz) == 2;
						if (perimeter) want = y >= hall + nether.hallHeight ? bricks : null; // open into the tunnel at the bottom
					} else {
						boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || y == floorY + 4;
						want = shell ? bricks : DeepTerrain.AIR;
						if (dz == 2 && Math.abs(dx) <= 1 && y <= floorY + 3) want = DeepTerrain.AIR; // the doorway into the corridor
					}
					if (want != null && level.getBlockState(at.set(x, y, z)) != want) level.setBlock(at, want, Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	/**
	 * (Re)builds the parts of the shrine and its corridors that lie in the 3x3 chunk region around
	 * {@code center}, right after that chunk's decoration. Every chunk whose region touches the
	 * shrine does this, so whatever a later-decorated neighbour writes over the shrine (fortress
	 * pieces, which are clipped to the chunk being decorated, or features spilling over from it) is
	 * put right again afterwards, no matter in which order the chunks are decorated or across how
	 * many chunks the shrine lies. Once a chunk is past decoration and lighting all its neighbours
	 * are decorated too, so this never touches a chunk a player can already see.
	 *
	 * <p>Every block is a pure function of its position and the seed, so rebuilding is idempotent.
	 */
	static void rebuild(WorldGenLevel level, ChunkPos center, StripBounds bounds, int layout) {
		final int minX = minX(bounds), maxX = minX + SIZE - 1, minZ = minZ(bounds, layout), maxZ = minZ + SIZE - 1;
		final int midX = minX + SIZE / 2 - 1, midZ = minZ + SIZE / 2 - 1; // the west / north of the two middle rows
		// the region this decoration step may write to: the chunk and its 8 neighbours
		final int regionMinX = (center.x() - 1) << 4, regionMaxX = ((center.x() + 1) << 4) + 15;
		final int regionMinZ = (center.z() - 1) << 4, regionMaxZ = ((center.z() + 1) << 4) + 15;
		int footMinX = Math.max(minX, regionMinX), footMaxX = Math.min(maxX, regionMaxX);
		int footMinZ = Math.max(minZ - CORRIDOR, regionMinZ), footMaxZ = Math.min(maxZ + CORRIDOR, regionMaxZ);
		if (layout >= 3) stairShaft(level, bounds, layout, regionMinX, regionMaxX, regionMinZ, regionMaxZ);
		if (footMinX > footMaxX || footMinZ > footMaxZ) return;

		long seed = level.getSeed();
		final int floorY = floorY(layout), ceilY = floorY + (CEIL_Y - FLOOR_Y);
		BlockState bricks = Blocks.NETHER_BRICKS.defaultBlockState();
		BlockState red = Blocks.RED_NETHER_BRICKS.defaultBlockState();
		BlockState fence = Blocks.NETHER_BRICK_FENCE.defaultBlockState();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

		for (int x = footMinX; x <= footMaxX; x++) {
			for (int z = footMinZ; z <= footMaxZ; z++) {
				boolean room = z >= minZ && z <= maxZ;
				if (!room && (x < midX - 1 || x > midX + 2)) continue; // corridors are 4 wide
				for (int y = floorY; y <= ceilY; y++) {
					BlockState state;
					if (room) {
						boolean shell = x == minX || x == maxX || z == minZ || z == maxZ || y == floorY || y == ceilY;
						boolean doorZ = (z == minZ || z == maxZ) && (x == midX || x == midX + 1) && y > floorY && y <= floorY + 3;
						boolean doorX = (x == minX || x == maxX) && (z == midZ || z == midZ + 1) && y > floorY && y <= floorY + 3;
						if (!shell || doorZ || doorX) {
							state = DeepTerrain.AIR;
						} else if (y == ceilY && (x + z) % 3 == 0) {
							state = Blocks.SHROOMLIGHT.defaultBlockState();
						} else {
							state = brick(seed, x, y, z, bricks, red);
						}
					} else {
						// corridor along the strip from a z door, carved through whatever is there
						if (y > floorY + 3) continue;
						boolean side = x == midX - 1 || x == midX + 2;
						state = y == floorY ? bricks : side && y == floorY + 1 ? fence : DeepTerrain.AIR;
					}
					put(level, at.set(x, y, z), state);
				}
			}
		}

		// pedestal + key chest
		BlockPos chest = chestPos(bounds, layout);
		if (inRegion(chest, regionMinX, regionMaxX, regionMinZ, regionMaxZ)) {
			put(level, chest.below(), Blocks.CHISELED_NETHER_BRICKS.defaultBlockState());
			put(level, chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
			if (level.getBlockEntity(chest) instanceof ChestBlockEntity be) {
				be.setItem(13, new ItemStack(ModContent.NETHER_KEY));
				be.setItem(3, new ItemStack(Items.GOLD_INGOT, 6));
				be.setItem(5, new ItemStack(Items.BLAZE_ROD, 2));
			}
		}

		// guards and light
		RandomSource random = new XoroshiroRandomSource(seed ^ 0x5EA1L);
		for (BlockPos spawner : new BlockPos[] {new BlockPos(minX + 1, floorY + 1, minZ + 1), new BlockPos(maxX - 1, floorY + 1, maxZ - 1)}) {
			if (!inRegion(spawner, regionMinX, regionMaxX, regionMinZ, regionMaxZ)) continue;
			if (put(level, spawner, Blocks.SPAWNER.defaultBlockState()) && level.getBlockEntity(spawner) instanceof SpawnerBlockEntity be) {
				be.setEntityId(EntityTypes.BLAZE, random);
			}
		}
		for (BlockPos lantern : new BlockPos[] {new BlockPos(maxX - 1, floorY + 1, minZ + 1), new BlockPos(minX + 1, floorY + 1, maxZ - 1)}) {
			if (inRegion(lantern, regionMinX, regionMaxX, regionMinZ, regionMaxZ)) put(level, lantern, Blocks.SOUL_LANTERN.defaultBlockState());
		}
	}

	/** Nether bricks with a quarter red ones, picked by position so every rebuild agrees. */
	private static BlockState brick(long seed, int x, int y, int z, BlockState bricks, BlockState red) {
		long hash = Strip.mix(seed ^ 0x5EA1L, Mth.getSeed(x, y, z));
		return (hash >>> 62) == 0 ? red : bricks;
	}

	/** Sets a block unless it is already there (keeps the chest's and spawners' block entities). */
	private static boolean put(WorldGenLevel level, BlockPos pos, BlockState state) {
		if (level.getBlockState(pos) == state) return false;
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		return true;
	}

	private static boolean inRegion(BlockPos pos, int minX, int maxX, int minZ, int maxZ) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}
}
