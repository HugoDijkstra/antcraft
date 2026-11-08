package dev.antcraft.mixin;

import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.NetherStripChunkGenerator;
import dev.antcraft.StripChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla only calls an overworld "stable" when it uses the vanilla overworld dimension type.
 * Without this, every AntCraft world would get the "experimental settings" warning (and a backup
 * prompt on every load).
 */
@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsMixin {
	@Inject(method = "isStableOverworld", at = @At("HEAD"), cancellable = true)
	private static void antcraft$stripIsStable(LevelStem dimension, CallbackInfoReturnable<Boolean> cir) {
		if (dimension.generator() instanceof StripChunkGenerator) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "isStableNether", at = @At("HEAD"), cancellable = true)
	private static void antcraft$netherStripIsStable(LevelStem dimension, CallbackInfoReturnable<Boolean> cir) {
		if (dimension.generator() instanceof NetherStripChunkGenerator) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "isStableEnd", at = @At("HEAD"), cancellable = true)
	private static void antcraft$endStripIsStable(LevelStem dimension, CallbackInfoReturnable<Boolean> cir) {
		if (dimension.generator() instanceof EndStripChunkGenerator) {
			cir.setReturnValue(true);
		}
	}
}
