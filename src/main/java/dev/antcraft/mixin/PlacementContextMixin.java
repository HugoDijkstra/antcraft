package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antcraft.FrameGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 11. Feature placements that scan down to the world bottom (count_on_every_layer, the
 * heightmap placement's lower bound) stop at the bottom of the vanilla frame in a framed world.
 */
@Mixin(PlacementContext.class)
public abstract class PlacementContextMixin {
	@Shadow
	public abstract ChunkGenerator generator();

	@ModifyReturnValue(method = "getMinY", at = @At("RETURN"))
	private int antcraft$clampToFrame(int minY) {
		return this.generator() instanceof FrameGenerator frame && frame.framed() ? Math.max(minY, frame.frameMinY()) : minY;
	}
}
