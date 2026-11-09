package dev.antcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Hook 7. Mansions need ground at y 60 or higher: a vanilla height, moved with the frame. */
@Mixin(WoodlandMansionStructure.class)
public abstract class WoodlandMansionMixin {
	@ModifyConstant(method = "findGenerationPoint", constant = @Constant(intValue = 60))
	private int antcraft$minGround(int y, @Local(argsOnly = true) Structure.GenerationContext context) {
		return y + WorldGenShift.anchorShift(context.chunkGenerator());
	}
}
