package dev.antcraft.mixin;

import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the underground desert pyramid keep its hand-picked height: a scattered feature piece with
 * a non-negative {@code heightPosition} is not projected onto the heightmap (the real surface).
 */
@Mixin(ScatteredFeaturePiece.class)
public interface ScatteredFeaturePieceAccessor {
	@Accessor("heightPosition")
	void antcraft$setHeightPosition(int heightPosition);
}
