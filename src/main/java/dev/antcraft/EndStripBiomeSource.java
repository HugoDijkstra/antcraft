package dev.antcraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * The tall End's biomes (End layout 2): the vanilla End biome source for the band y 0..255 and
 * everywhere outside the tier zone (no shift, D_E = 0), and each tier's own biome above and below
 * the band inside the tier zone (z -1400..-300). The vanilla End biomes do not depend on y, so the
 * vanilla source is only ever sampled inside the band.
 */
public final class EndStripBiomeSource extends BiomeSource {
	public static final MapCodec<EndStripBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				BiomeSource.CODEC.fieldOf("base").forGetter(s -> s.base),
				Codec.intRange(2, EndLayout.LATEST).fieldOf("layout").forGetter(s -> s.layout.id),
				RegistryOps.retrieveGetter(Registries.BIOME)
			)
			.apply(i, EndStripBiomeSource::new)
	);

	private final BiomeSource base;
	private final EndLayout layout;
	/** Tier biome per tier index of {@link EndLayout#tiers}. */
	private final Holder<Biome>[] tierBiomes;
	/** Tier index by quart y (offset {@link #minQuart}), -1 in the vanilla band. */
	private final int[] tierByQuart;
	private final int minQuart;
	private static final int BAND_MIN_QUART = QuartPos.fromBlock(EndLayout.BASE_MIN_Y), BAND_MAX_QUART = QuartPos.fromBlock(EndLayout.BASE_MAX_Y);
	private static final int ZONE_MIN_QUART = QuartPos.fromBlock(EndLayout.ZONE_MIN_Z), ZONE_MAX_QUART = QuartPos.fromBlock(EndLayout.ZONE_MAX_Z);

	public EndStripBiomeSource(BiomeSource base, int layout, HolderGetter<Biome> biomes) {
		this.base = base;
		this.layout = EndLayout.byId(layout);
		@SuppressWarnings("unchecked") Holder<Biome>[] holders = new Holder[this.layout.tiers.size()];
		for (int i = 0; i < holders.length; i++) holders[i] = biomes.getOrThrow(this.layout.tiers.get(i).biome());
		this.tierBiomes = holders;
		this.minQuart = QuartPos.fromBlock(this.layout.worldMinY);
		int maxQuart = QuartPos.fromBlock(this.layout.worldMaxY);
		this.tierByQuart = new int[maxQuart - this.minQuart + 1];
		for (int q = this.minQuart; q <= maxQuart; q++) {
			EndLayout.Tier tier = this.layout.tierAt(QuartPos.toBlock(q));
			this.tierByQuart[q - this.minQuart] = tier == null ? -1 : this.layout.tiers.indexOf(tier);
		}
	}

	public BiomeSource base() {
		return this.base;
	}

	public int layout() {
		return this.layout.id;
	}

	@Override
	protected MapCodec<? extends BiomeSource> codec() {
		return CODEC;
	}

	/** The vanilla End's biomes, then the tier biomes (all vanilla End biomes too, so the feature order is the vanilla one). */
	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(this.base.possibleBiomes().stream(), Stream.of(this.tierBiomes)).distinct();
	}

	/** The tier's biome for a quart position, or null where the vanilla End's biome applies. */
	private Holder<Biome> tierBiome(int qy, int qz) {
		if (qz < ZONE_MIN_QUART || qz > ZONE_MAX_QUART || (qy >= BAND_MIN_QUART && qy <= BAND_MAX_QUART)) return null;
		int i = qy - this.minQuart;
		int tier = i >= 0 && i < this.tierByQuart.length ? this.tierByQuart[i] : -1;
		return tier >= 0 ? this.tierBiomes[tier] : null;
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		BiomeResolver vanilla = this.base.createResolver(sampler);
		return (qx, qy, qz) -> {
			Holder<Biome> tier = this.tierBiome(qy, qz);
			return tier != null ? tier : vanilla.getNoiseBiome(qx, Math.clamp(qy, BAND_MIN_QUART, BAND_MAX_QUART), qz);
		};
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int sizeX, int sizeY, int sizeZ) {
		// only the vanilla band is sampled (the vanilla End biomes do not depend on y)
		int lo = Math.clamp(minQuartY, BAND_MIN_QUART, BAND_MAX_QUART);
		int hi = Math.clamp(minQuartY + sizeY - 1, BAND_MIN_QUART, BAND_MAX_QUART);
		BiomeResolver vanilla = this.base.createResolverForChunk(sampler, minQuartX, lo, minQuartZ, sizeX, hi - lo + 1, sizeZ);
		return (qx, qy, qz) -> {
			Holder<Biome> tier = this.tierBiome(qy, qz);
			return tier != null ? tier : vanilla.getNoiseBiome(qx, Math.clamp(qy, lo, hi), qz);
		};
	}
}
