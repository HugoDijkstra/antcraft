package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * The End portal room at the bottom of the world. It replaces the stronghold, which would never
 * generate inside the strip. Blocks are placed during terrain generation; the spawner and chests
 * (which need block entities) during decoration.
 */
public final class PortalRoom {
	private PortalRoom() {}

	/** Centre column of the 3x3 portal. */
	public static final int PORTAL_X = 9;

	/** Room shell, inclusive. */
	static final int MIN_X = 1, MAX_X = 18;
	public static final int HALF_LENGTH = 9; // z from cz-9 to cz+8
	public static final int HEIGHT = 9; // floor y .. floor y + 9 (ceiling)


	private static BlockState wall(RandomSource random) {
		float f = random.nextFloat();
		if (f < 0.2F) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
		if (f < 0.35F) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
		return Blocks.STONE_BRICKS.defaultBlockState();
	}

	/** Positions of block-entity blocks, relative to the seed. */
	static BlockPos spawnerPos(long seed) {
		return new BlockPos(PORTAL_X, Strip.portalRoomFloorY() + 1, Strip.portalRoomZ(seed) + 5);
	}

	static BlockPos chestA(long seed) {
		return new BlockPos(MIN_X + 1, Strip.portalRoomFloorY() + 1, Strip.portalRoomZ(seed) - HALF_LENGTH + 1);
	}

	static BlockPos chestB(long seed) {
		return new BlockPos(MAX_X - 1, Strip.portalRoomFloorY() + 1, Strip.portalRoomZ(seed) + HALF_LENGTH - 2);
	}

	/** Writes the room's blocks into a chunk during terrain generation. */
	public static void build(ChunkAccess chunk, long seed) {
		ChunkPos pos = chunk.getPos();
		int cz = Strip.portalRoomZ(seed);
		int minZ = cz - HALF_LENGTH, maxZ = cz + HALF_LENGTH - 1;
		int floorY = Strip.portalRoomFloorY();
		int ceilY = floorY + HEIGHT;
		if (pos.getMaxBlockZ() < minZ || pos.getMinBlockZ() > maxZ || pos.getMaxBlockX() < MIN_X || pos.getMinBlockX() > MAX_X) return;

		// Same random sequence for every chunk, so the room is consistent across chunk borders.
		RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 4000));
		boolean[] eyes = new boolean[12];
		for (int i = 0; i < eyes.length; i++) eyes[i] = random.nextFloat() > 0.9F;

		for (int x = MIN_X; x <= MAX_X; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = floorY; y <= ceilY; y++) {
					BlockState wallState = wall(random); // always draw, keeps the sequence chunk-independent
					boolean shell = x == MIN_X || x == MAX_X || z == minZ || z == maxZ || y == floorY || y == ceilY;
					boolean doorway = (z == minZ || z == maxZ) && x >= PORTAL_X - 1 && x <= PORTAL_X + 2 && y >= floorY + 1 && y <= floorY + 3;
					put(chunk, pos, x, y, z, shell && !doorway ? wallState : DeepTerrain.AIR);
				}
			}
		}

		// raised platform with a lava pool under the portal
		BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
		for (int x = PORTAL_X - 3; x <= PORTAL_X + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				boolean inner = Math.abs(x - PORTAL_X) <= 1 && Math.abs(z - cz) <= 1;
				put(chunk, pos, x, floorY + 1, z, inner ? DeepTerrain.LAVA : bricks);
			}
		}
		// stairs up onto the platform on both long sides
		BlockState stairsN = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH);
		BlockState stairsS = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH);
		for (int x = PORTAL_X - 1; x <= PORTAL_X + 1; x++) {
			put(chunk, pos, x, floorY + 1, cz - 4, stairsN);
			put(chunk, pos, x, floorY + 1, cz + 4, stairsS);
		}

		// End portal frame: every frame faces the centre, like vanilla
		int fy = floorY + 2;
		BlockState frame = Blocks.END_PORTAL_FRAME.defaultBlockState();
		int i = 0;
		for (int x = PORTAL_X - 1; x <= PORTAL_X + 1; x++) {
			put(chunk, pos, x, fy, cz - 2, frame.setValue(EndPortalFrameBlock.FACING, Direction.SOUTH).setValue(EndPortalFrameBlock.HAS_EYE, eyes[i++]));
			put(chunk, pos, x, fy, cz + 2, frame.setValue(EndPortalFrameBlock.FACING, Direction.NORTH).setValue(EndPortalFrameBlock.HAS_EYE, eyes[i++]));
		}
		for (int z = cz - 1; z <= cz + 1; z++) {
			put(chunk, pos, PORTAL_X - 2, fy, z, frame.setValue(EndPortalFrameBlock.FACING, Direction.EAST).setValue(EndPortalFrameBlock.HAS_EYE, eyes[i++]));
			put(chunk, pos, PORTAL_X + 2, fy, z, frame.setValue(EndPortalFrameBlock.FACING, Direction.WEST).setValue(EndPortalFrameBlock.HAS_EYE, eyes[i++]));
		}

		// a few lanterns so the room can be seen from the hall
		BlockState lantern = Blocks.LANTERN.defaultBlockState();
		put(chunk, pos, MIN_X + 1, floorY + 1, maxZ - 1, lantern);
		put(chunk, pos, MAX_X - 1, floorY + 1, minZ + 1, lantern);
	}

	private static void put(ChunkAccess chunk, ChunkPos pos, int x, int y, int z, BlockState state) {
		if (x < pos.getMinBlockX() || x > pos.getMaxBlockX() || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ()) return;
		DeepTerrain.set(chunk, x - pos.getMinBlockX(), y, z - pos.getMinBlockZ(), state);
	}

	/** Places the silverfish spawner and loot chests if they fall in the chunk being decorated. */
	public static void placeBlockEntities(WorldGenLevel level, ChunkAccess chunk, long seed) {
		ChunkPos pos = chunk.getPos();
		RandomSource random = new XoroshiroRandomSource(Strip.mix(seed, 4001), Strip.mix(pos.x(), pos.z()));

		BlockPos spawner = spawnerPos(seed);
		if (contains(pos, spawner)) {
			level.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_CLIENTS);
			if (level.getBlockEntity(spawner) instanceof SpawnerBlockEntity be) {
				be.setEntityId(EntityTypes.SILVERFISH, random);
			}
		}
		placeChest(level, pos, chestA(seed), Direction.SOUTH, random);
		placeChest(level, pos, chestB(seed), Direction.NORTH, random);
	}

	private static void placeChest(WorldGenLevel level, ChunkPos pos, BlockPos chest, Direction facing, RandomSource random) {
		if (!contains(pos, chest)) return;
		level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), Block.UPDATE_CLIENTS);
		RandomizableContainer.setBlockEntityLootTable(level, random, chest, BuiltInLootTables.STRONGHOLD_CORRIDOR);
	}

	private static boolean contains(ChunkPos pos, BlockPos block) {
		return block.getX() >= pos.getMinBlockX() && block.getX() <= pos.getMaxBlockX() && block.getZ() >= pos.getMinBlockZ() && block.getZ() <= pos.getMaxBlockZ();
	}
}
