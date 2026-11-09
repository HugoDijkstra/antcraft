package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.FrameTerrain;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hook 1. Structure starts live in real coordinates, but inside a terrain frame the noise runs in
 * vanilla coordinates: move the beard boxes and junctions down by the frame offset. A no-op for
 * every chunk that is not a {@link FrameTerrain.FrameProtoChunk} (all old worlds, all vanilla worlds).
 * Lithium leaves {@code createNoiseChunk} alone.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseChunkBeardMixin {
	@WrapOperation(
		method = "createNoiseChunk",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Beardifier;forStructuresInChunk(Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/levelgen/Beardifier;")
	)
	private Beardifier antcraft$shiftBeard(StructureManager structureManager, ChunkPos pos, Operation<Beardifier> original, @Local(argsOnly = true) ChunkAccess chunk) {
		Beardifier beard = original.call(structureManager, pos);
		if (!(chunk instanceof FrameTerrain.FrameProtoChunk frame) || beard == Beardifier.EMPTY) return beard;
		BeardifierAccessor acc = (BeardifierAccessor) beard;
		BoundingBox affected = acc.antcraft$affectedBox();
		if (affected == null) return beard;
		int dy = -frame.offset;
		List<Beardifier.Rigid> rigids = new ArrayList<>();
		for (Beardifier.Rigid r : acc.antcraft$pieces()) rigids.add(new Beardifier.Rigid(r.box().moved(0, dy, 0), r.terrainAdjustment(), r.groundLevelDelta()));
		List<JigsawJunction> junctions = new ArrayList<>();
		for (JigsawJunction j : acc.antcraft$junctions()) {
			junctions.add(new JigsawJunction(j.getSourceX(), j.getSourceGroundY() + dy, j.getSourceZ(), j.getDeltaY(), j.getDestProjection()));
		}
		return new Beardifier(List.copyOf(rigids), List.copyOf(junctions), affected.moved(0, dy, 0));
	}
}
