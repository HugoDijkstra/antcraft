package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.AntCraft;
import dev.antcraft.AntCraftConfig;
import dev.antcraft.Strip;
import dev.antcraft.StripLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Monsters spawn more often the deeper you are: in the strip overworld, a chunk near a player in
 * deep layer L gets 1 + L / 2 monster spawn passes per spawn cycle instead of 1 (layer 8: 5; L is
 * the depth scaled to 8 layers, {@link StripLayout#scaledLayer}). The
 * normal mob cap still applies on every pass.
 */
@Mixin(NaturalSpawner.class)
public abstract class DeepSpawnRateMixin {
	private static final double MAX_DISTANCE_SQR = 128.0 * 128.0;

	@WrapOperation(
		method = "spawnForChunk",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/NaturalSpawner;spawnCategoryForChunk(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V"
		)
	)
	private static void antcraft$moreSpawnsDeeper(
		MobCategory category, ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback,
		Operation<Void> original, @Local(argsOnly = true) NaturalSpawner.SpawnState state
	) {
		original.call(category, level, chunk, predicate, callback);
		if (category != MobCategory.MONSTER || !AntCraftConfig.get().spawnMobsNearPlayers) return;
		if (level.dimension() != Level.OVERWORLD || !AntCraft.isStripLevel(level)) return;
		StripLayout layout = Strip.layout(level);
		if (layout == null) return;
		int layer = nearestPlayerLayer(level, layout, chunk);
		for (int pass = 0; pass < layout.scaledLayer(layer) / 2; pass++) {
			if (!((SpawnStateAccessor) state).antcraft$canSpawnForCategoryLocal(category, chunk.getPos())) return;
			original.call(category, level, chunk, predicate, callback);
		}
	}

	private static int nearestPlayerLayer(ServerLevel level, StripLayout layout, LevelChunk chunk) {
		double cx = chunk.getPos().getMiddleBlockX(), cz = chunk.getPos().getMiddleBlockZ();
		ServerPlayer nearest = null;
		double best = MAX_DISTANCE_SQR;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) continue;
			double dx = player.getX() - cx, dz = player.getZ() - cz;
			double d = dx * dx + dz * dz;
			if (d <= best) {
				best = d;
				nearest = player;
			}
		}
		if (nearest == null || nearest.getBlockY() >= layout.vanillaMinY) return 0;
		return Math.max(0, layout.layerAt(nearest.getBlockY()));
	}
}
