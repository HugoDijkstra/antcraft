package dev.antcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a template piece's position (the deep ocean ruins, {@link OceanRuinDeepMixin}). */
@Mixin(TemplateStructurePiece.class)
public interface TemplateStructurePieceAccessor {
	@Accessor("templatePosition")
	BlockPos antcraft$templatePosition();

	/** The template's name (the tall End's tier cities look for the "ship"). */
	@Accessor("templateName")
	String antcraft$templateName();
}
