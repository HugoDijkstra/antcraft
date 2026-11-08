package dev.antcraft.mixin;

import dev.antcraft.Strip;
import dev.antcraft.StripChunkGenerator;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Some vanilla ore features range from the world bottom (redstone, buried lapis, small iron, the
 * diamonds). In the deep strip they would scatter ores into every layer and break the
 * one-tier-per-layer progression of {@code DeepTerrain}'s ore table, so they are skipped below the
 * first floor's hall. Non-ore blobs (gravel, tuff, infested stone, ...) still generate.
 */
@Mixin({OreFeature.class, ScatteredOreFeature.class})
public abstract class DeepOreMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void antcraft$noVanillaOresInDeepLayers(
		WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir
	) {
		if (!(generator instanceof StripChunkGenerator) || origin.getY() >= Strip.hallBottom(0) + Strip.HALL_HEIGHT) return;
		for (BlockReplacement target : ((AbstractOreFeature) (Object) this).targetStates()) {
			if (target.state().is(ConventionalBlockTags.ORES)) {
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
