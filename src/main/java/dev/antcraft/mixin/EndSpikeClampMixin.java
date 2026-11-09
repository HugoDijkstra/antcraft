package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.EndLayout;
import dev.antcraft.EndStripChunkGenerator;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 12. A pillar is built from the world's bottom up ({@code placeSpike} fills obsidian from
 * {@code getMinY()} to the pillar's height). In the tall End that would be y -2032: the pillars
 * start at the vanilla band's bottom (0) instead, at worldgen and when the dragon is respawned.
 */
@Mixin(EndSpikeFeature.class)
public abstract class EndSpikeClampMixin {
	@WrapOperation(
		method = "placeSpike",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerLevelAccessor;getMinY()I")
	)
	private int antcraft$spikeFromBandBottom(ServerLevelAccessor level, Operation<Integer> original) {
		int minY = original.call(level);
		return EndStripChunkGenerator.tallEnd(level.getLevel()) != null ? Math.max(minY, EndLayout.BASE_MIN_Y) : minY;
	}
}
