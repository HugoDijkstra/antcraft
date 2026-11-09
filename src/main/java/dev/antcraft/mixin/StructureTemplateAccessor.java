package dev.antcraft.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a template's palettes (the Trial Vault's start-template table, {@link dev.antcraft.VaultPlan}). */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
	@Accessor("palettes")
	List<StructureTemplate.Palette> antcraft$palettes();
}
