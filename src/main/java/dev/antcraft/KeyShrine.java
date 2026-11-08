package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

	static final int MIN_X = 5, MAX_X = 14;
	static final int MIN_Z = 50, MAX_Z = 59;
	static final int FLOOR_Y = 70, CEIL_Y = 76;
	static final int CORRIDOR = 14;
	/** The whole shrine (not the corridors) lies in this chunk. */
	static final ChunkPos CHUNK = new ChunkPos(0, 3);

	public static BlockPos chestPos() {
		return new BlockPos(9, FLOOR_Y + 2, 54);
	}

	static void placeIfHere(WorldGenLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
		if (!chunk.getPos().equals(CHUNK)) return;
		RandomSource random = new XoroshiroRandomSource(level.getSeed() ^ 0x5EA1L);
		BlockState bricks = Blocks.NETHER_BRICKS.defaultBlockState();
		BlockState red = Blocks.RED_NETHER_BRICKS.defaultBlockState();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

		for (int x = MIN_X; x <= MAX_X; x++) {
			for (int z = MIN_Z; z <= MAX_Z; z++) {
				for (int y = FLOOR_Y; y <= CEIL_Y; y++) {
					boolean shell = x == MIN_X || x == MAX_X || z == MIN_Z || z == MAX_Z || y == FLOOR_Y || y == CEIL_Y;
					boolean doorZ = (z == MIN_Z || z == MAX_Z) && (x == 9 || x == 10) && y > FLOOR_Y && y <= FLOOR_Y + 3;
					boolean doorX = (x == MIN_X || x == MAX_X) && (z == 54 || z == 55) && y > FLOOR_Y && y <= FLOOR_Y + 3;
					BlockState state;
					if (!shell || doorZ || doorX) {
						state = DeepTerrain.AIR;
					} else if (y == CEIL_Y && (x + z) % 3 == 0) {
						state = Blocks.SHROOMLIGHT.defaultBlockState();
					} else {
						state = random.nextFloat() < 0.25F ? red : bricks;
					}
					level.setBlock(at.set(x, y, z), state, Block.UPDATE_CLIENTS);
				}
			}
		}

		// corridors along the strip from both z doors, carved through whatever is there
		for (int i = 1; i <= CORRIDOR; i++) {
			for (int z : new int[] {MIN_Z - i, MAX_Z + i}) {
				for (int x = 8; x <= 11; x++) {
					boolean side = x == 8 || x == 11;
					level.setBlock(at.set(x, FLOOR_Y, z), bricks, Block.UPDATE_CLIENTS);
					for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
						level.setBlock(at.set(x, y, z), side ? (y == FLOOR_Y + 1 ? Blocks.NETHER_BRICK_FENCE.defaultBlockState() : DeepTerrain.AIR) : DeepTerrain.AIR, Block.UPDATE_CLIENTS);
					}
				}
			}
		}

		// pedestal + key chest
		level.setBlock(at.set(9, FLOOR_Y + 1, 54), Blocks.CHISELED_NETHER_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
		BlockPos chest = chestPos();
		level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), Block.UPDATE_CLIENTS);
		if (level.getBlockEntity(chest) instanceof ChestBlockEntity be) {
			be.setItem(13, new ItemStack(ModContent.NETHER_KEY));
			be.setItem(3, new ItemStack(Items.GOLD_INGOT, 6));
			be.setItem(5, new ItemStack(Items.BLAZE_ROD, 2));
		}

		// guards and light
		for (BlockPos spawner : new BlockPos[] {new BlockPos(6, FLOOR_Y + 1, 51), new BlockPos(13, FLOOR_Y + 1, 58)}) {
			level.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_CLIENTS);
			if (level.getBlockEntity(spawner) instanceof SpawnerBlockEntity be) be.setEntityId(EntityTypes.BLAZE, random);
		}
		level.setBlock(at.set(13, FLOOR_Y + 1, 51), Blocks.SOUL_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
		level.setBlock(at.set(6, FLOOR_Y + 1, 58), Blocks.SOUL_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
	}
}
