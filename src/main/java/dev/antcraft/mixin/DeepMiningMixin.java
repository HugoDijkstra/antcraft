package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antcraft.Strip;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rock gets harder the deeper you go: in deep layer N, pickaxe blocks take (1 + N x h) times as long
 * to mine, h = {@code miningHardnessPerLayer} (0.5 by default: 1.5x in layer 1 up to 5x in layer 8).
 * Runs on both client and server so the breaking animation matches; the client uses the value the
 * server sent ({@link dev.antcraft.MiningHardness}). Vanilla worlds never go below y -64, so this
 * only affects AntCraft worlds.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class DeepMiningMixin {
	@ModifyReturnValue(method = "getDestroyProgress", at = @At("RETURN"))
	private float antcraft$harderWhenDeeper(float progress, Player player, BlockGetter level, BlockPos pos) {
		if (pos.getY() >= Strip.VANILLA_MIN_Y || player.level().dimension() != Level.OVERWORLD) return progress;
		if (!((BlockBehaviour.BlockStateBase) (Object) this).is(BlockTags.MINEABLE_WITH_PICKAXE)) return progress;
		int layer = Strip.layerAt(pos.getY());
		return layer >= 1 ? progress / Strip.hardness(layer, player.level().isClientSide()) : progress;
	}
}
