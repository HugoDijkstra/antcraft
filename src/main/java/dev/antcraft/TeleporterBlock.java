package dev.antcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The teleporter pad in a boss room, or on the surface next to the spawn ({@link ArenaManager}). It
 * is dark until the room's guardian is defeated. Standing on a lit pad, jumping goes up one boss room
 * (from the first: to the surface) and crouching goes down to the next defeated one; right-clicking
 * goes up too.
 */
public class TeleporterBlock extends Block {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public TeleporterBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
			if (!state.getValue(LIT)) {
				serverPlayer.sendOverlayMessage(Component.translatableWithFallback(
					"block.antcraft.teleporter.inactive", "The teleporter wakes up when this room's guardian is defeated"
				));
			} else {
				ArenaManager.teleportUp(serverLevel, serverPlayer, pos);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
