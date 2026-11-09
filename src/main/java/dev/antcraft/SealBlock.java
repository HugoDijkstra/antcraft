package dev.antcraft;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Covers the hole of a bedrock floor. Unbreakable. A seal with a key item (Nether Seal, Ancient
 * Seal) turns back into the ladder that leads down when right-clicked with that key; a seal
 * without a key (Guardian Seal) opens when the floor's guardian dies ({@link ArenaManager}).
 *
 * <p>Chat messages use {@code <description id>.locked} / {@code .opened}.
 */
public class SealBlock extends Block {
	private final @Nullable Supplier<Item> key;
	/** AntCraft advancement granted (criterion "opened") when a player opens it with the key; null for none. */
	private final @Nullable String advancement;

	public SealBlock(Properties properties, @Nullable Supplier<Item> key, @Nullable String advancement) {
		super(properties);
		this.key = key;
		this.advancement = advancement;
	}

	private boolean isKey(ItemStack stack) {
		return this.key != null && stack.is(this.key.get());
	}

	@Override
	protected InteractionResult useItemOn(
		ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult
	) {
		if (!this.isKey(stack)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (!level.isClientSide()) {
			this.openWithKey(level, pos);
			stack.consume(1, player);
			player.sendOverlayMessage(Component.translatable(this.getDescriptionId() + ".opened"));
			if (this.advancement != null && player instanceof ServerPlayer serverPlayer) LayerTracker.award(serverPlayer, this.advancement, "opened");
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		// a key in the off-hand: let the off-hand pass reach useItemOn
		if (this.isKey(player.getOffhandItem())) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide()) {
			player.sendOverlayMessage(Component.translatable(this.getDescriptionId() + ".locked"));
		}
		return InteractionResult.SUCCESS;
	}

	/** What the key does: the seal becomes the ladder of its hole ({@link VoidSealBlock}: a doorway). */
	protected void openWithKey(Level level, BlockPos pos) {
		open(level, pos);
	}

	/** Turns the seal at {@code pos} into the ladder of the hole (it hangs on the bedrock to its south). */
	public static void open(Level level, BlockPos pos) {
		level.setBlock(pos, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.6F);
		level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
	}
}
