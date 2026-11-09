package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antcraft.Strip;
import dev.antcraft.StripLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rock gets harder the deeper you go: in deep layer N, pickaxe blocks take (1 + d x h) times as long
 * to mine, h = {@code miningHardnessPerLayer} (0.5 by default), d = the depth scaled to 8 layers
 * ({@link StripLayout#hardnessScale}; d = N in layout 1: 1.5x in layer 1 up to 5x in layer 8).
 * Runs on both client and server so the breaking animation matches; the client uses the value the
 * server sent ({@link dev.antcraft.MiningHardness}). Vanilla worlds never go below y -64, so this
 * only affects AntCraft worlds.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class DeepMiningMixin {
	@ModifyReturnValue(method = "getDestroyProgress", at = @At("RETURN"))
	private float antcraft$harderWhenDeeper(float progress, Player player, BlockGetter level, BlockPos pos) {
		if (player.level().dimension() != Level.OVERWORLD) return progress;
		StripLayout layout = Strip.layoutOrDefault(player.level());
		if (pos.getY() >= layout.vanillaMinY) return progress;
		if (!((BlockBehaviour.BlockStateBase) (Object) this).is(BlockTags.MINEABLE_WITH_PICKAXE)) return progress;
		int layer = layout.layerAt(pos.getY());
		return layer >= 1 ? progress / Strip.hardness(layout, layer, player.level().isClientSide()) : progress;
	}
}
