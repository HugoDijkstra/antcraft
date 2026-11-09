package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.WorldGenShift;
import java.util.Optional;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 4. A jigsaw start projected to a heightmap (villages, outposts, trail ruins, camps) uses its
 * start height as an offset from the surface, not as a world height: no shift for it.
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStartHeightMixin {
	@Shadow
	@Final
	private Optional<Heightmap.Types> projectStartToHeightmap;

	@WrapOperation(
		method = "findGenerationPoint",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/heightproviders/HeightProvider;sample(Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)I")
	)
	private int antcraft$projectedStartsUnshifted(HeightProvider provider, RandomSource random, WorldGenerationContext context, Operation<Integer> original) {
		if (this.projectStartToHeightmap.isEmpty()) return original.call(provider, random, context);
		WorldGenShift.pushSuppress();
		try {
			return original.call(provider, random, context);
		} finally {
			WorldGenShift.popSuppress();
		}
	}
}
