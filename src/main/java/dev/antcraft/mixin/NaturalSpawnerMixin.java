package dev.antcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.antcraft.AntCraft;
import dev.antcraft.AntCraftConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Natural spawning picks a random y between the world bottom and the surface. The strip overworld
 * is ~2400 blocks tall, so almost every attempt lands kilometres away from anyone and the deep
 * layers stay nearly empty. In the strip overworld the y is picked within 48 blocks of the nearest
 * player instead ({@link AntCraftConfig#spawnMobsNearPlayers}), as in a normal-height world.
 *
 * <p>Lithium does not overwrite {@code getRandomPosWithin} (it only redirects the entity iteration
 * in {@code createState}), so wrapping the call inside it is safe.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	private static final int RANGE = 48;
	/** Spawning never happens further than 128 blocks from a player anyway. */
	private static final double MAX_DISTANCE_SQR = 128.0 * 128.0;

	@WrapOperation(
		method = "getRandomPosWithin",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;randomBetweenInclusive(Lnet/minecraft/util/RandomSource;II)I")
	)
	private static int antcraft$nearPlayers(
		RandomSource random, int min, int max, Operation<Integer> original,
		@Local(argsOnly = true) Level level, @Local(ordinal = 0) int x, @Local(ordinal = 1) int z
	) {
		if (!AntCraftConfig.get().spawnMobsNearPlayers || !(level instanceof ServerLevel server) || !AntCraft.isStripLevel(server)
			|| (server.dimension() != Level.OVERWORLD && server.getHeight() <= 256)) {
			// the strip overworld, and every tall strip dimension (the tall Nether is 4064 blocks high)
			return original.call(random, min, max);
		}
		ServerPlayer nearest = null;
		double best = MAX_DISTANCE_SQR;
		for (ServerPlayer player : server.players()) {
			if (player.isSpectator()) continue;
			double dx = player.getX() - x, dz = player.getZ() - z;
			double d = dx * dx + dz * dz;
			if (d <= best) {
				best = d;
				nearest = player;
			}
		}
		if (nearest == null) return original.call(random, min, max);
		int py = nearest.getBlockY();
		// the caller skips y < minY + 1
		int lo = Math.max(min + 1, py - RANGE), hi = Math.min(max, py + RANGE);
		if (lo > hi) return original.call(random, min, max);
		return original.call(random, lo, hi);
	}
}
