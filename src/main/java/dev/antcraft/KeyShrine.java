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

	/** North wall z: a sixth of the way along the strip (z 50..59 for 300 long), 2 blocks into a chunk. */
	static int minZ(StripBounds bounds) {
		return ((bounds.scaledZ(50) >> 4) << 4) + 2;
	}

	public static BlockPos chestPos(StripBounds bounds) {
		return new BlockPos(minX(bounds) + 4, FLOOR_Y + 2, minZ(bounds) + 4);
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
	static void rebuild(WorldGenLevel level, ChunkPos center, StripBounds bounds) {
		final int minX = minX(bounds), maxX = minX + SIZE - 1, minZ = minZ(bounds), maxZ = minZ + SIZE - 1;
		final int midX = minX + SIZE / 2 - 1, midZ = minZ + SIZE / 2 - 1; // the west / north of the two middle rows
		// the region this decoration step may write to: the chunk and its 8 neighbours
		final int regionMinX = (center.x() - 1) << 4, regionMaxX = ((center.x() + 1) << 4) + 15;
		final int regionMinZ = (center.z() - 1) << 4, regionMaxZ = ((center.z() + 1) << 4) + 15;
		int footMinX = Math.max(minX, regionMinX), footMaxX = Math.min(maxX, regionMaxX);
		int footMinZ = Math.max(minZ - CORRIDOR, regionMinZ), footMaxZ = Math.min(maxZ + CORRIDOR, regionMaxZ);
		if (footMinX > footMaxX || footMinZ > footMaxZ) return;

		long seed = level.getSeed();
		BlockState bricks = Blocks.NETHER_BRICKS.defaultBlockState();
		BlockState red = Blocks.RED_NETHER_BRICKS.defaultBlockState();
		BlockState fence = Blocks.NETHER_BRICK_FENCE.defaultBlockState();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

		for (int x = footMinX; x <= footMaxX; x++) {
			for (int z = footMinZ; z <= footMaxZ; z++) {
				boolean room = z >= minZ && z <= maxZ;
				if (!room && (x < midX - 1 || x > midX + 2)) continue; // corridors are 4 wide
				for (int y = FLOOR_Y; y <= CEIL_Y; y++) {
					BlockState state;
					if (room) {
						boolean shell = x == minX || x == maxX || z == minZ || z == maxZ || y == FLOOR_Y || y == CEIL_Y;
						boolean doorZ = (z == minZ || z == maxZ) && (x == midX || x == midX + 1) && y > FLOOR_Y && y <= FLOOR_Y + 3;
						boolean doorX = (x == minX || x == maxX) && (z == midZ || z == midZ + 1) && y > FLOOR_Y && y <= FLOOR_Y + 3;
						if (!shell || doorZ || doorX) {
							state = DeepTerrain.AIR;
						} else if (y == CEIL_Y && (x + z) % 3 == 0) {
							state = Blocks.SHROOMLIGHT.defaultBlockState();
						} else {
							state = brick(seed, x, y, z, bricks, red);
						}
					} else {
						// corridor along the strip from a z door, carved through whatever is there
						if (y > FLOOR_Y + 3) continue;
						boolean side = x == midX - 1 || x == midX + 2;
						state = y == FLOOR_Y ? bricks : side && y == FLOOR_Y + 1 ? fence : DeepTerrain.AIR;
					}
					put(level, at.set(x, y, z), state);
				}
			}
		}

		// pedestal + key chest
		BlockPos chest = chestPos(bounds);
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
		for (BlockPos spawner : new BlockPos[] {new BlockPos(minX + 1, FLOOR_Y + 1, minZ + 1), new BlockPos(maxX - 1, FLOOR_Y + 1, maxZ - 1)}) {
			if (!inRegion(spawner, regionMinX, regionMaxX, regionMinZ, regionMaxZ)) continue;
			if (put(level, spawner, Blocks.SPAWNER.defaultBlockState()) && level.getBlockEntity(spawner) instanceof SpawnerBlockEntity be) {
				be.setEntityId(EntityTypes.BLAZE, random);
			}
		}
		for (BlockPos lantern : new BlockPos[] {new BlockPos(maxX - 1, FLOOR_Y + 1, minZ + 1), new BlockPos(minX + 1, FLOOR_Y + 1, maxZ - 1)}) {
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
