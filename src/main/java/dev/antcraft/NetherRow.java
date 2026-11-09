package dev.antcraft;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * One deep Nether layer (spec v2 4.2): its name, biome, blocks, cavern shape, light, ores and
 * natural monsters. Layers N1..N19 of {@link NetherLayout#LAYOUT_3}.
 *
 * @param mobs MONSTER-category mobs that spawn naturally instead of the biome's (null = the biome's;
 *     empty = none). Only monsters belong here: the list replaces the MONSTER spawn cycle, so a
 *     creature (a strider) would bypass the creature cap and never despawn. Striders come from the
 *     layer biome's own CREATURE list (every Nether biome has them).
 */
public record NetherRow(
	String key,
	String englishName,
	ResourceKey<Biome> biome,
	BlockState base,
	BlockState patch,
	double patchThreshold,
	@Nullable BlockState ground,
	Shape shape,
	double threshold,
	int lavaHeight,
	BlockState crust,
	BlockState light,
	double lightRate,
	double quartz,
	double gold,
	int debris,
	@Nullable List<Spawn> mobs
) {
	/** How the caverns of a layer are carved ({@link DeepNetherTerrain}). */
	public enum Shape {
		/** Big simplex caverns, stretched 3.5x vertically. */
		OPEN,
		/** Noodle tunnels only. */
		MAZE,
		/** Tall vertical caverns. */
		SHAFTS,
		/** A huge open cavern with floating islets. */
		ISLANDS,
		/** Open caverns over terraced floors. */
		STEPPED,
		/** Smaller caverns joined by tunnels. */
		MIXED,
		/** One huge open chasm along the strip. */
		CHASM
	}

	public record Spawn(EntityType<?> type, int weight, int min, int max) {}

	public Component displayName() {
		return Component.translatableWithFallback("antcraft.nether_layer." + this.key, this.englishName);
	}

	private static Spawn s(EntityType<?> type, int weight, int min, int max) {
		return new Spawn(type, weight, min, max);
	}

	private static BlockState b(net.minecraft.world.level.block.Block block) {
		return block.defaultBlockState();
	}

	private static ResourceKey<Biome> biome(String path) {
		return ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(path));
	}

	/** Every deep layer's distinct content, besides this table, lives in {@link DeepNetherTerrain} (by layer index). */
	static NetherRow[] table(NetherLayout layout) {
		if (!layout.tall()) return new NetherRow[] {null};
		BlockState netherrack = b(Blocks.NETHERRACK), blackstone = b(Blocks.BLACKSTONE), glowstone = b(Blocks.GLOWSTONE), shroom = b(Blocks.SHROOMLIGHT);
		BlockState lantern = b(Blocks.SOUL_LANTERN);
		NetherRow[] rows = new NetherRow[] {
			null,
			// N1..N19
			new NetherRow("ashen_wastes", "Ashen Wastes", Biomes.NETHER_WASTES, netherrack, b(Blocks.MAGMA_BLOCK), 0.55, null,
				Shape.OPEN, 0.18, 17, blackstone, glowstone, 0.012, 2, 2, 0, null),
			new NetherRow("crimson_depths", "Crimson Depths", Biomes.CRIMSON_FOREST, netherrack, b(Blocks.CRIMSON_NYLIUM), 0.6, b(Blocks.CRIMSON_NYLIUM),
				Shape.OPEN, 0.16, 17, blackstone, shroom, 0.010, 1, 1, 0, null),
			new NetherRow("netherrack_warrens", "Netherrack Warrens", Biomes.NETHER_WASTES, netherrack, b(Blocks.NETHER_WART_BLOCK), 0.45, null,
				Shape.MAZE, 0.0, 17, blackstone, glowstone, 0.02, 1, 1, 0,
				List.of(s(EntityTypes.MAGMA_CUBE, 40, 2, 4), s(EntityTypes.ZOMBIFIED_PIGLIN, 100, 2, 4))),
			new NetherRow("soul_valley", "Soul Valley", Biomes.SOUL_SAND_VALLEY, b(Blocks.SOUL_SOIL), b(Blocks.SOUL_SAND), 0.3, b(Blocks.SOUL_SAND),
				Shape.OPEN, 0.12, 17, blackstone, lantern, 0.0, 1, 1, 1, null),
			new NetherRow("warped_sanctuary", "Warped Sanctuary", Biomes.WARPED_FOREST, netherrack, b(Blocks.WARPED_WART_BLOCK), 0.62, b(Blocks.WARPED_NYLIUM),
				Shape.OPEN, 0.14, 17, blackstone, shroom, 0.012, 1, 1, 0, List.of()),
			new NetherRow("glowstone_grotto", "Glowstone Grotto", Biomes.NETHER_WASTES, netherrack, glowstone, 0.58, null,
				Shape.OPEN, 0.06, 17, glowstone, glowstone, 0.03, 1, 1, 1, List.of(s(EntityTypes.GHAST, 50, 1, 2), s(EntityTypes.ZOMBIFIED_PIGLIN, 30, 2, 4))),
			new NetherRow("basalt_rift", "Basalt Rift", Biomes.BASALT_DELTAS, b(Blocks.BASALT), blackstone, 0.35, null,
				Shape.OPEN, 0.14, 17, blackstone, shroom, 0.006, 1, 1, 3, null),
			new NetherRow("wart_terraces", "Wart Terraces", Biomes.CRIMSON_FOREST, b(Blocks.RED_NETHER_BRICKS), b(Blocks.NETHERRACK), 0.5, b(Blocks.SOUL_SAND),
				Shape.STEPPED, 0.14, 17, blackstone, shroom, 0.01, 1, 1, 1, List.of(s(EntityTypes.PIGLIN, 100, 2, 4), s(EntityTypes.ZOMBIFIED_PIGLIN, 40, 2, 4))),
			new NetherRow("infernal_keep", "Infernal Keep", Biomes.NETHER_WASTES, b(Blocks.NETHER_BRICKS), netherrack, 0.0, null,
				Shape.OPEN, 0.16, 17, blackstone, glowstone, 0.008, 1, 1, 2, null),
			new NetherRow("lava_archipelago", "Lava Archipelago", Biomes.BASALT_DELTAS, b(Blocks.BASALT), blackstone, 0.4, b(Blocks.BLACKSTONE),
				Shape.ISLANDS, 0.52, 60, blackstone, shroom, 0.004, 1, 1, 2, List.of(s(EntityTypes.MAGMA_CUBE, 40, 1, 3))),
			new NetherRow("piglin_mines", "Piglin Mines", Biomes.CRIMSON_FOREST, blackstone, b(Blocks.GILDED_BLACKSTONE), 0.62, null,
				Shape.MIXED, 0.26, 17, blackstone, b(Blocks.LANTERN), 0.01, 1, 3, 2, List.of(s(EntityTypes.PIGLIN, 100, 2, 4), s(EntityTypes.HOGLIN, 20, 2, 3))),
			new NetherRow("gilded_halls", "Gilded Halls", Biomes.CRIMSON_FOREST, blackstone, b(Blocks.POLISHED_BLACKSTONE_BRICKS), 0.5, null,
				Shape.OPEN, 0.16, 17, blackstone, b(Blocks.LANTERN), 0.01, 1, 1, 2, List.of(s(EntityTypes.PIGLIN, 100, 2, 4))),
			new NetherRow("ghast_expanse", "Ghast Expanse", Biomes.SOUL_SAND_VALLEY, netherrack, b(Blocks.SOUL_SOIL), 0.55, null,
				Shape.ISLANDS, 0.58, 17, blackstone, glowstone, 0.0, 1, 1, 2, List.of(s(EntityTypes.GHAST, 100, 1, 3))),
			new NetherRow("tangled_shafts", "Tangled Shafts", Biomes.WARPED_FOREST, b(Blocks.WARPED_WART_BLOCK), netherrack, 0.45, b(Blocks.WARPED_NYLIUM),
				Shape.SHAFTS, 0.22, 17, blackstone, shroom, 0.01, 1, 1, 2, null),
			new NetherRow("quartz_halls", "Quartz Halls", Biomes.NETHER_WASTES, netherrack, b(Blocks.SMOOTH_QUARTZ), 0.7, null,
				Shape.OPEN, 0.15, 17, blackstone, glowstone, 0.008, 4, 1, 3, List.of(s(EntityTypes.ZOMBIFIED_PIGLIN, 100, 2, 4), s(EntityTypes.MAGMA_CUBE, 10, 1, 2))),
			new NetherRow("portal_graveyard", "Portal Graveyard", Biomes.NETHER_WASTES, b(Blocks.OBSIDIAN), b(Blocks.CRYING_OBSIDIAN), 0.55, b(Blocks.NETHERRACK),
				Shape.OPEN, 0.16, 17, blackstone, glowstone, 0.008, 1, 1, 3, List.of(s(EntityTypes.PIGLIN, 100, 2, 4), s(EntityTypes.ZOMBIFIED_PIGLIN, 40, 2, 4))),
			new NetherRow("soulfire_chasm", "Soulfire Chasm", Biomes.SOUL_SAND_VALLEY, b(Blocks.SOUL_SOIL), b(Blocks.BONE_BLOCK), 0.6, b(Blocks.SOUL_SOIL),
				Shape.CHASM, 0.0, 17, blackstone, lantern, 0.0, 1, 1, 3, List.of(s(EntityTypes.SKELETON, 100, 2, 4), s(EntityTypes.GHAST, 40, 1, 2))),
			new NetherRow("molten_cascades", "Molten Cascades", Biomes.BASALT_DELTAS, b(Blocks.MAGMA_BLOCK), b(Blocks.OBSIDIAN), 0.5, b(Blocks.MAGMA_BLOCK),
				Shape.OPEN, 0.12, 17, blackstone, shroom, 0.004, 1, 1, 3, List.of(s(EntityTypes.GHAST, 50, 1, 2), s(EntityTypes.MAGMA_CUBE, 100, 2, 4))),
			new NetherRow("ember_forge", "The Ember Forge", Biomes.BASALT_DELTAS, blackstone, b(Blocks.MAGMA_BLOCK), 0.55, null,
				Shape.OPEN, 0.12, 17, blackstone, shroom, 0.006, 1, 1, 3, List.of(s(EntityTypes.BLAZE, 60, 1, 3), s(EntityTypes.MAGMA_CUBE, 100, 2, 4)))
		};
		if (rows.length != layout.deepLayers + 1) throw new IllegalStateException("Nether layout " + layout.id + " needs " + (layout.deepLayers + 1) + " rows");
		return rows;
	}
}
