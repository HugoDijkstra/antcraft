package dev.antcraft;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/**
 * Wraps the vanilla overworld biome source. Above the first bedrock floor everything is vanilla;
 * below it, each layer is cave biome except for its forest band, which gets a forest biome that
 * changes with depth.
 */
public final class StripBiomeSource extends BiomeSource {
	public static final MapCodec<StripBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				BiomeSource.CODEC.fieldOf("base").forGetter(s -> s.base),
				RegistryOps.retrieveElement(Biomes.DRIPSTONE_CAVES),
				RegistryOps.retrieveElement(Biomes.FOREST),
				RegistryOps.retrieveElement(Biomes.TAIGA),
				RegistryOps.retrieveElement(Biomes.DARK_FOREST),
				RegistryOps.retrieveElement(Biomes.JUNGLE),
				RegistryOps.retrieveElement(Biomes.CHERRY_GROVE),
				RegistryOps.retrieveElement(Biomes.DESERT),
				RegistryOps.retrieveElement(Biomes.MANGROVE_SWAMP),
				RegistryOps.retrieveElement(Biomes.MUSHROOM_FIELDS),
				RegistryOps.retrieveElement(Biomes.DEEP_DARK)
			)
			.apply(i, StripBiomeSource::new)
	);

	private final BiomeSource base;
	private final Holder<Biome> caves;
	private final Holder<Biome> deepDark;
	/** forestBiomes.get(layer - 1). */
	private final List<Holder<Biome>> forestBiomes;

	public StripBiomeSource(
		BiomeSource base,
		Holder<Biome> caves,
		Holder<Biome> forest,
		Holder<Biome> taiga,
		Holder<Biome> darkForest,
		Holder<Biome> jungle,
		Holder<Biome> cherry,
		Holder<Biome> desert,
		Holder<Biome> mangrove,
		Holder<Biome> mushroom,
		Holder<Biome> deepDark
	) {
		this.base = base;
		this.caves = caves;
		this.deepDark = deepDark;
		this.forestBiomes = List.of(forest, taiga, darkForest, jungle, cherry, desert, mangrove, mushroom);
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(this.base.possibleBiomes().stream(), Stream.concat(Stream.of(this.caves, this.deepDark), this.forestBiomes.stream())).distinct();
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		BiomeResolver vanilla = this.base.createResolver(sampler);
		return (qx, qy, qz) -> {
			Holder<Biome> deep = this.deepBiome(QuartPos.toBlock(qy));
			return deep != null ? deep : vanilla.getNoiseBiome(qx, qy, qz);
		};
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int sizeX, int sizeY, int sizeZ) {
		// The vanilla source samples its climate noise eagerly for the whole requested volume. Only
		// ask it for the vanilla height range; everything below is decided by deepBiome().
		int lo = Math.max(minQuartY, QuartPos.fromBlock(Strip.VANILLA_MIN_Y));
		int hi = Math.max(lo + 1, minQuartY + sizeY);
		BiomeResolver vanilla = this.base.createResolverForChunk(sampler, minQuartX, lo, minQuartZ, sizeX, hi - lo, sizeZ);
		return (qx, qy, qz) -> {
			Holder<Biome> deep = this.deepBiome(QuartPos.toBlock(qy));
			return deep != null ? deep : vanilla.getNoiseBiome(qx, Math.clamp(qy, lo, hi - 1), qz);
		};
	}

	/** Biome for a block y below the vanilla world, or null to use vanilla. */
	private Holder<Biome> deepBiome(int y) {
		if (y >= Strip.VANILLA_MIN_Y) return null;
		int layer = Strip.layerAt(y);
		if (layer >= 1) {
			int floor = Strip.forestFloor(layer);
			if (y >= floor - 8 && y <= floor + Strip.FOREST_HEIGHT + 4) {
				return this.forestBiomes.get(layer - 1);
			}
			if (layer == Strip.CITY_LAYER) {
				return this.deepDark;
			}
		}
		return this.caves;
	}
}
