package dev.antcraft;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;

/**
 * Everything one deep layer of a {@link StripLayout} contains. Rows are per layout, not per theme:
 * the same theme may get different stones, trees or plants in another layout.
 */
public record LayerSpec(
	@Nullable LayerTheme theme,
	BlockState baseStone,
	BlockState patchStone,
	double patchThreshold,
	@Nullable ResourceKey<Biome> forestBiome,
	@Nullable ResourceKey<Biome> caveBiome,
	@Nullable ResourceKey<Structure> villageStructure,
	double pondThreshold,
	BlockState pondBottom,
	BlockState lightBlock,
	Puddle puddle,
	List<Ore> ores,
	List<TreeChoice> trees,
	int treeAttempts,
	PlantPalette plants,
	GroundPalette ground,
	Animals animals,
	Mineshaft mineshaft,
	double dungeonRate,
	boolean hasForest,
	List<String> extraStructures
) {
	/** One weighted tree feature of a forest. */
	public record TreeChoice(ResourceKey<Feature> feature, int weight) {}

	/**
	 * An ore vein table row: {@code count(layer)} veins per 16x16 chunk per layer (before the deep
	 * ore multiplier), in layers minLayer..maxLayer.
	 */
	public record Ore(BlockState stone, BlockState deepslate, int minLayer, int maxLayer, int veins, int perLayer, int size) {
		public int count(int layer) {
			return layer < this.minLayer || layer > this.maxLayer ? 0 : this.veins + this.perLayer * (layer - this.minLayer);
		}

		public static Ore of(Block stone, Block deepslate, int minLayer, int maxLayer, int veins, int perLayer, int size) {
			return new Ore(stone.defaultBlockState(), deepslate.defaultBlockState(), minLayer, maxLayer, veins, perLayer, size);
		}
	}

	/** What fills the carved caves' floor puddles. */
	public enum Puddle { WATER_OR_LAVA, LAVA }

	/** Ground plants of the forest floor (the first six are layout 1's). */
	public enum PlantPalette { MEADOW, TAIGA, CHERRY, DESERT, SWAMP, MUSHROOM, AUTUMN, BADLANDS, DARKWOOD, LUSH, JUNGLE, SAVANNA, PALE, CRAGS, EMBER, NONE }

	/** The forest floor's top block (the first four are layout 1's). */
	public enum GroundPalette { GRASS, SAND, MUD_AND_GRASS, MYCELIUM, PODZOL_MIX, COARSE_MIX, RED_SAND, MOSS, PALE_MOSS, STONE_MIX, SCULK, SNOW, WARPED_NYLIUM }

	/**
	 * Animals spawned in the forest at generation (NONE: a silent layer). The first four are
	 * layout 1's; the others belong to the tall layout's themes.
	 */
	public enum Animals { FARM, DESERT, MOOSHROOM, NONE, TAIGA, AUTUMN, BADLANDS, DARKWOOD, LUSH, JUNGLE, SAVANNA, CRAGS, CRYSTAL, REEF, SWAMP, FROZEN }

	/** Hand-started mineshaft in the cave band. */
	public enum Mineshaft { NONE, NORMAL, MESA }
}
