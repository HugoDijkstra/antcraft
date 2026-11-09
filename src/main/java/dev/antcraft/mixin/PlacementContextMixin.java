package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.EndLayout;
import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.FrameGenerator;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 11. Feature placements that scan down to the world bottom (count_on_every_layer, the
 * heightmap placement's lower bound) stop at the bottom of the vanilla frame in a framed world.
 *
 * <p>The tall End (End layout 2, D_E = 0) gets the same treatment for its vanilla band 0..255:
 * the bottom is 0 and heightmap heights are those of the band
 * ({@link EndStripChunkGenerator#baseBandHeight}), so the vanilla End features (chorus, gateway
 * returns, end islands) land exactly where they would in a 0..255 End and never on a tier.
 */
@Mixin(PlacementContext.class)
public abstract class PlacementContextMixin {
	@Shadow
	public abstract ChunkGenerator generator();

	@Shadow
	public abstract WorldGenLevel getLevel();

	@ModifyReturnValue(method = "getMinY", at = @At("RETURN"))
	private int antcraft$clampToFrame(int minY) {
		if (EndStripChunkGenerator.isTallEnd(this.generator())) return Math.max(minY, EndLayout.BASE_MIN_Y);
		return this.generator() instanceof FrameGenerator frame && frame.framed() ? Math.max(minY, frame.frameMinY()) : minY;
	}

	@ModifyReturnValue(method = "getHeight", at = @At("RETURN"))
	private int antcraft$endBandHeight(
		int height, @Local(argsOnly = true) Heightmap.Types type, @Local(argsOnly = true, ordinal = 0) int x, @Local(argsOnly = true, ordinal = 1) int z
	) {
		if (!EndStripChunkGenerator.isTallEnd(this.generator())) return height;
		return EndStripChunkGenerator.baseBandHeight(this.getLevel(), type, x, z, height);
	}
}
