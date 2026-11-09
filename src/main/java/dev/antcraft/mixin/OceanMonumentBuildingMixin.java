package dev.antcraft.mixin;

import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Hook 6. The monument building sits at the hard-coded y 39; in a framed world it moves with the frame. */
@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public abstract class OceanMonumentBuildingMixin {
	@ModifyConstant(method = "<init>(Lnet/minecraft/util/RandomSource;IILnet/minecraft/core/Direction;)V", constant = @Constant(intValue = 39))
	private static int antcraft$monumentY(int y) {
		return y + WorldGenShift.monumentDy();
	}
}
