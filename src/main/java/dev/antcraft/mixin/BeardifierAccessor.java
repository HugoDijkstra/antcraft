package dev.antcraft.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
	@Accessor("pieces")
	List<Beardifier.Rigid> antcraft$pieces();

	@Accessor("junctions")
	List<JigsawJunction> antcraft$junctions();

	@Accessor("affectedBox")
	@Nullable BoundingBox antcraft$affectedBox();
}
