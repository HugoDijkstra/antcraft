package dev.antcraft.mixin;

import dev.antcraft.AntCraft;
import dev.antcraft.Strip;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Structures placed during world generation (village houses, fortresses) spawn their mobs with the
 * structure. Parts of them that lie outside the strip are cut away, so their mobs would just fall
 * into the void: don't spawn them in the first place.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void antcraft$onlyInsideStrip(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!Strip.inside(entity.getBlockX(), entity.getBlockZ()) && AntCraft.isStripLevel(this.level)) {
			cir.setReturnValue(false);
		}
	}
}
