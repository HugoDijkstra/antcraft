package dev.antcraft.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.antcraft.Strip;
import dev.antcraft.StripLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Hook 15 (client). The sky's horizon (vanilla: y 63) follows the moved sea level in tall strip overworlds. */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class HorizonMixin {
	@Shadow
	@Final
	private boolean isFlat;

	@ModifyReturnValue(method = "getHorizonHeight", at = @At("RETURN"))
	private double antcraft$tallHorizon(double horizon, LevelHeightAccessor level) {
		if (this.isFlat || !(level instanceof Level l)) return horizon;
		StripLayout layout = Strip.layout(l);
		return layout != null && layout.frameOffset != 0 ? horizon + layout.frameOffset : horizon;
	}
}
