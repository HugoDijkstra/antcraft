package dev.antcraft.mixin;

import dev.antcraft.AnchorShiftHolder;
import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook 2. Every generation context remembers how far absolute anchors move for its generator
 * ({@link WorldGenShift#anchorShift}): the frame offset of a tall strip generator, 0 for anything
 * else. Read from the generator, never from a ThreadLocal that could cross threads (except the
 * override set around our own deep starts on the structure thread).
 */
@Mixin(WorldGenerationContext.class)
public abstract class WorldGenerationContextMixin implements AnchorShiftHolder {
	@Unique
	private int antcraft$anchorShift;

	@Inject(method = "<init>(Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/LevelHeightAccessor;)V", at = @At("TAIL"))
	private void antcraft$rememberShift(ChunkGenerator generator, LevelHeightAccessor heightAccessor, CallbackInfo ci) {
		this.antcraft$anchorShift = WorldGenShift.anchorShift(generator);
	}

	@Override
	public int antcraft$anchorShift() {
		return this.antcraft$anchorShift;
	}
}
