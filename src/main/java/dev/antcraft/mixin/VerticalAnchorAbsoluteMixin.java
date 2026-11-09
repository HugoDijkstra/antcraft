package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antcraft.AnchorShiftHolder;
import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 3. An absolute anchor ("y 16", "y -27") means a vanilla height: in a framed world it is
 * that many blocks higher. Relative anchors (above bottom, below top, sea level) already follow
 * the reported frame. 0 shift in every old and every vanilla world.
 */
@Mixin(VerticalAnchor.Absolute.class)
public abstract class VerticalAnchorAbsoluteMixin {
	@ModifyReturnValue(method = "resolveY", at = @At("RETURN"))
	private int antcraft$shift(int y, WorldGenerationContext context) {
		int shift = ((AnchorShiftHolder) context).antcraft$anchorShift();
		return shift == 0 || WorldGenShift.suppressed() ? y : y + shift;
	}
}
