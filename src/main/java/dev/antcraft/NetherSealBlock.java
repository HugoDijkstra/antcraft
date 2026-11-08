package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Covers the only hole of bedrock floor 4. Unbreakable; right-click it with a Nether Key (found in
 * the Nether strip) and it turns back into the ladder that leads down.
 */
public class NetherSealBlock extends Block {
	public NetherSealBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useItemOn(
		ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult
	) {
		if (!stack.is(ModContent.NETHER_KEY)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (!level.isClientSide()) {
			level.setBlock(pos, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
			level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.6F);
			level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
			stack.consume(1, player);
			player.sendOverlayMessage(Component.translatable("block.antcraft.nether_seal.opened"));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		// a key in the off-hand: let the off-hand pass reach useItemOn
		if (player.getOffhandItem().is(ModContent.NETHER_KEY)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide()) {
			player.sendOverlayMessage(Component.translatable("block.antcraft.nether_seal.locked"));
		}
		return InteractionResult.SUCCESS;
	}
}
