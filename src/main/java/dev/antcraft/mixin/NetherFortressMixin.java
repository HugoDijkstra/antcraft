package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.WorldGenShift;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hook 9. Fortresses are moved into y 48..70: vanilla heights, moved with the frame. */
@Mixin(NetherFortressStructure.class)
public abstract class NetherFortressMixin {
	@WrapOperation(
		method = "generatePieces",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePiecesBuilder;moveInsideHeights(Lnet/minecraft/util/RandomSource;II)V")
	)
	private static void antcraft$shiftHeights(
		StructurePiecesBuilder builder, RandomSource random, int lowest, int highest, Operation<Void> original, @Local(argsOnly = true) Structure.GenerationContext context
	) {
		int s = WorldGenShift.anchorShift(context.chunkGenerator());
		original.call(builder, random, lowest + s, highest + s);
	}
}
