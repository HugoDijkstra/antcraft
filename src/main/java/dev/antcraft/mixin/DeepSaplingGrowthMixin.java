package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.AntCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Saplings grow about twice as fast in the deep layers (a 1-in-3 instead of 1-in-7 chance per random tick). */
@Mixin(SaplingBlock.class)
public abstract class DeepSaplingGrowthMixin {
	@WrapOperation(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
	private int antcraft$fasterDeepSaplings(
		RandomSource random, int bound, Operation<Integer> original,
		BlockState state, ServerLevel level, BlockPos pos, RandomSource tickRandom
	) {
		return original.call(random, AntCraft.isDeepFarm(level, pos) ? 3 : bound);
	}
}
