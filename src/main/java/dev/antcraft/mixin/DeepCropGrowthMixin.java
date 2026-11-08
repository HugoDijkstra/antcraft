package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.AntCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Crops (wheat, carrots, potatoes, beetroot) grow {@link AntCraft#DEEP_CROP_GROWTH}x faster in the deep layers, which have no sun. */
@Mixin(CropBlock.class)
public abstract class DeepCropGrowthMixin {
	@WrapOperation(
		method = "randomTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/CropBlock;getGrowthSpeed(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F")
	)
	private float antcraft$fasterDeepCrops(
		Block type, BlockGetter getter, BlockPos pos, Operation<Float> original,
		BlockState state, ServerLevel level, BlockPos tickPos, RandomSource random
	) {
		float speed = original.call(type, getter, pos);
		return AntCraft.isDeepFarm(level, pos) ? speed * AntCraft.DEEP_CROP_GROWTH : speed;
	}
}
