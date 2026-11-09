package dev.antcraft.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The zoom seed, so {@link dev.antcraft.FrameTerrain.ShiftedBiomeManager} zooms exactly like the region's manager. */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
	@Accessor("biomeZoomSeed")
	long antcraft$getBiomeZoomSeed();
}
