package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
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
				RegistryOps.retrieveElement(Biomes.DEEP_DARK),
				Codec.intRange(1, StripLayout.LATEST).optionalFieldOf("layout", 1).forGetter(s -> s.layout.id),
				RegistryOps.retrieveGetter(Registries.BIOME)
			)
			.apply(i, StripBiomeSource::new)
	);

	private final BiomeSource base;
	private final StripLayout layout;
	/** For the layer biomes of later layouts (lookups only, nothing is written). */
	private final HolderGetter<Biome> biomes;
	private final Holder<Biome> caves;
	private final Holder<Biome> deepDark;
	/** forestBiomes.get(layer - 1). */
	private final List<Holder<Biome>> forestBiomes;
	/** Tall layouts: the vanilla band starts at this quart y, and moves up by this many quarts. */
	private final int bandMinQuart, shiftQuarts;
	/** Tall layouts: each row's forest and cave biome (index = layer). */
	private final Holder<Biome>[] rowForest, rowCave;

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
		Holder<Biome> deepDark,
		int layout,
		HolderGetter<Biome> biomes
	) {
		this.base = base;
		this.layout = StripLayout.byId(layout);
		this.biomes = biomes;
		this.caves = caves;
		this.deepDark = deepDark;
		this.forestBiomes = List.of(forest, taiga, darkForest, jungle, cherry, desert, mangrove, mushroom);
		this.bandMinQuart = QuartPos.fromBlock(this.layout.vanillaMinY);
		this.shiftQuarts = this.layout.frameOffset >> 2;
		if (this.layout.id >= 2) {
			@SuppressWarnings("unchecked") Holder<Biome>[] f = new Holder[this.layout.deepLayers + 1];
			@SuppressWarnings("unchecked") Holder<Biome>[] c = new Holder[this.layout.deepLayers + 1];
			for (int l = 1; l <= this.layout.deepLayers; l++) {
				LayerSpec spec = this.layout.layer(l);
				f[l] = biomes.getOrThrow(spec.forestBiome());
				c[l] = biomes.getOrThrow(spec.caveBiome());
			}
			this.rowForest = f;
			this.rowCave = c;
		} else {
			this.rowForest = null;
			this.rowCave = null;
		}
	}

	public int layout() {
		return this.layout.id;
	}

	/** The wrapped vanilla biome source. */
	public BiomeSource base() {
		return this.base;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		if (this.rowForest != null) {
			// tall layouts: every row's forest and cave biome, in layer order (all vanilla overworld biomes plus ember_rift)
			Stream.Builder<Holder<Biome>> rows = Stream.builder();
			for (int l = 1; l <= this.layout.deepLayers; l++) {
				rows.add(this.rowForest[l]);
				rows.add(this.rowCave[l]);
			}
			return Stream.concat(this.base.possibleBiomes().stream(), Stream.concat(Stream.of(this.caves, this.deepDark), rows.build())).distinct();
		}
		return Stream.concat(this.base.possibleBiomes().stream(), Stream.concat(Stream.of(this.caves, this.deepDark), this.forestBiomes.stream())).distinct();
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		BiomeResolver vanilla = this.base.createResolver(sampler);
		int shift = this.shiftQuarts;
		return (qx, qy, qz) -> {
			Holder<Biome> deep = this.deepBiome(QuartPos.toBlock(qy));
			return deep != null ? deep : vanilla.getNoiseBiome(qx, qy - shift, qz);
		};
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int sizeX, int sizeY, int sizeZ) {
		if (this.shiftQuarts != 0) {
			// tall layouts: the vanilla band is the vanilla source D blocks lower; only sample the band
			int lo = Math.max(minQuartY, this.bandMinQuart);
			int hi = Math.max(lo + 1, minQuartY + sizeY);
			int shift = this.shiftQuarts;
			BiomeResolver vanilla = this.base.createResolverForChunk(sampler, minQuartX, lo - shift, minQuartZ, sizeX, hi - lo, sizeZ);
			return (qx, qy, qz) -> {
				Holder<Biome> deep = this.deepBiome(QuartPos.toBlock(qy));
				return deep != null ? deep : vanilla.getNoiseBiome(qx, Math.clamp(qy, lo, hi - 1) - shift, qz);
			};
		}
		// The vanilla source samples its climate noise eagerly for the whole requested volume. Only
		// ask it for the vanilla height range; everything below is decided by deepBiome().
		int lo = Math.max(minQuartY, QuartPos.fromBlock(this.layout.vanillaMinY));
		int hi = Math.max(lo + 1, minQuartY + sizeY);
		BiomeResolver vanilla = this.base.createResolverForChunk(sampler, minQuartX, lo, minQuartZ, sizeX, hi - lo, sizeZ);
		return (qx, qy, qz) -> {
			Holder<Biome> deep = this.deepBiome(QuartPos.toBlock(qy));
			return deep != null ? deep : vanilla.getNoiseBiome(qx, Math.clamp(qy, lo, hi - 1), qz);
		};
	}

	/** Biome for a block y below the vanilla world, or null to use vanilla. */
	private Holder<Biome> deepBiome(int y) {
		if (y >= this.layout.vanillaMinY) return null;
		if (this.rowForest != null) {
			int layer = this.layout.layerAt(y);
			if (layer < 1) return this.caves;
			if (this.layout.isVault(layer)) return this.rowCave[layer]; // dripstone caves throughout
			int floor = this.layout.forestFloor(layer);
			return y >= floor - 4 && y <= floor + this.layout.forestHeight ? this.rowForest[layer] : this.rowCave[layer];
		}
		int layer = this.layout.layerAt(y);
		if (layer >= 1) {
			int floor = this.layout.forestFloor(layer);
			if (y >= floor - 8 && y <= floor + this.layout.forestHeight + 4) {
				return this.forestBiomes.get(layer - 1);
			}
			if (layer == this.layout.cityLayer) {
				return this.deepDark;
			}
		}
		return this.caves;
	}
}
