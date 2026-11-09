package dev.antcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Hook 8. The ruined portal's hard-coded heights (mountain 70, Nether 27..100) are vanilla heights. */
@Mixin(RuinedPortalStructure.class)
public abstract class RuinedPortalMixin {
	@ModifyConstant(
		method = "findSuitableY",
		constant = {@Constant(intValue = 70), @Constant(intValue = 27), @Constant(intValue = 29), @Constant(intValue = 32), @Constant(intValue = 100)}
	)
	private static int antcraft$shiftHeights(int y, @Local(argsOnly = true) ChunkGenerator generator) {
		return y + WorldGenShift.anchorShift(generator);
	}
}
