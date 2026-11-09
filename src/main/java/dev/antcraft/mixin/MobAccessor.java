package dev.antcraft.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@link dev.antcraft.ArenaManager} clear the persistence a split cube copies from a guardian. */
@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("persistenceRequired")
	void antcraft$setPersistenceRequired(boolean persistenceRequired);
}
