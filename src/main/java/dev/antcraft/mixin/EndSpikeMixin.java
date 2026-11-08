package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.EndStripChunkGenerator;
import dev.antcraft.StripBounds;
import java.util.List;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The single source of the pillar layout: worldgen, the crystal count, crystal reset and the
 * respawn rebuild all ask here. In the End strip the pillars stand inside the strip.
 */
@Mixin(EndSpikeFeature.class)
public abstract class EndSpikeMixin {
	@ModifyReturnValue(method = "getSpikesForLevel", at = @At("RETURN"))
	private static List<EndSpikeFeature.EndSpike> antcraft$spikesInStrip(List<EndSpikeFeature.EndSpike> original, @Local(argsOnly = true) WorldGenLevel level) {
		StripBounds bounds = EndStripChunkGenerator.endBounds(level.getLevel());
		return bounds != null ? EndStripChunkGenerator.strippedSpikes(original, bounds) : original;
	}
}
