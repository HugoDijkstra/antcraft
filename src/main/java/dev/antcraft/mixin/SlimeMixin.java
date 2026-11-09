package dev.antcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.Strip;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Hook 10. Swamp slimes spawn at y 51..69 and slime-chunk slimes below 40: vanilla heights. */
@Mixin(Slime.class)
public abstract class SlimeMixin {
	@ModifyConstant(method = "checkSlimeSpawnRules", constant = {@Constant(intValue = 50), @Constant(intValue = 70), @Constant(intValue = 40)})
	private static int antcraft$shiftHeights(int y, @Local(argsOnly = true) LevelAccessor level) {
		return level instanceof ServerLevelAccessor server ? y + Strip.frameOffset(server.getLevel().getChunkSource().getGenerator()) : y;
	}
}
