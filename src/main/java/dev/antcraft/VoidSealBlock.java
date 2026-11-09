package dev.antcraft;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * The Void Seal: the two-high door of the Crown Vault at the top of the tall End. The Void Key
 * opens it into a doorway (the whole door, not a ladder).
 */
public class VoidSealBlock extends SealBlock {
	public VoidSealBlock(Properties properties, @Nullable Supplier<Item> key, @Nullable String advancement) {
		super(properties, key, advancement);
	}

	@Override
	protected void openWithKey(Level level, BlockPos pos) {
		BlockPos bottom = pos;
		while (level.getBlockState(bottom.below()).is(this)) bottom = bottom.below();
		for (BlockPos p = bottom; level.getBlockState(p).is(this); p = p.above()) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.6F);
		level.playSound(null, pos, SoundEvents.END_GATEWAY_SPAWN, SoundSource.BLOCKS, 0.6F, 1.0F);
	}
}
