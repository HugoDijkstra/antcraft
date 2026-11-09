package dev.antcraft.mixin;

import dev.antcraft.WorldGenShift;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks 6b/6c. Sets how far the monument building moves: the generator's anchor shift when it is
 * generated, and the saved building's own offset from y 39 when its pieces are rebuilt after a load
 * (so a monument keeps its y in any world, 0 in vanilla ones).
 */
@Mixin(OceanMonumentStructure.class)
public abstract class OceanMonumentStructureMixin {
	@Inject(method = "generatePieces", at = @At("HEAD"))
	private static void antcraft$shiftOnGenerate(StructurePiecesBuilder builder, Structure.GenerationContext context, CallbackInfo ci) {
		WorldGenShift.setMonumentDy(WorldGenShift.anchorShift(context.chunkGenerator()));
	}

	@Inject(method = "generatePieces", at = @At("RETURN"))
	private static void antcraft$clearOnGenerate(StructurePiecesBuilder builder, Structure.GenerationContext context, CallbackInfo ci) {
		WorldGenShift.clearMonumentDy();
	}

	@Inject(method = "regeneratePiecesAfterLoad", at = @At("HEAD"))
	private static void antcraft$shiftOnLoad(ChunkPos chunkPos, long seed, PiecesContainer savedPieces, CallbackInfoReturnable<PiecesContainer> cir) {
		WorldGenShift.setMonumentDy(savedPieces.isEmpty() ? 0 : savedPieces.pieces().getFirst().getBoundingBox().minY() - 39);
	}

	@Inject(method = "regeneratePiecesAfterLoad", at = @At("RETURN"))
	private static void antcraft$clearOnLoad(ChunkPos chunkPos, long seed, PiecesContainer savedPieces, CallbackInfoReturnable<PiecesContainer> cir) {
		WorldGenShift.clearMonumentDy();
	}
}
