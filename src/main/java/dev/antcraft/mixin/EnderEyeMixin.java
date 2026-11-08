package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.antcraft.AntCraft;
import dev.antcraft.Strip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** In the strip overworld, eyes of ender lead to the End portal room at the bottom of the world. */
@Mixin(EnderEyeItem.class)
public abstract class EnderEyeMixin {
	@WrapOperation(
		method = "use",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;findNearestMapStructure(Lnet/minecraft/tags/TagKey;Lnet/minecraft/core/BlockPos;IZ)Lnet/minecraft/core/BlockPos;"
		)
	)
	private BlockPos antcraft$pointToPortalRoom(
		ServerLevel level, TagKey<Structure> tag, BlockPos origin, int radius, boolean createReference, Operation<BlockPos> original
	) {
		if (level.dimension() == Level.OVERWORLD && AntCraft.isStripLevel(level)) {
			return Strip.portalTarget(level.getSeed());
		}
		return original.call(level, tag, origin, radius, createReference);
	}
}
