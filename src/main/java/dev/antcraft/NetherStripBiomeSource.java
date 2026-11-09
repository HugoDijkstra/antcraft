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
 * The tall Nether's biomes (Nether layout 3): the vanilla Nether biome source, moved up by D_N =
 * 1904, for the band (y >= 1904), and each deep layer's own biome below it.
 */
public final class NetherStripBiomeSource extends BiomeSource {
	public static final MapCodec<NetherStripBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
				BiomeSource.CODEC.fieldOf("base").forGetter(s -> s.base),
				Codec.intRange(3, NetherLayout.LATEST).fieldOf("layout").forGetter(s -> s.layout.id),
				RegistryOps.retrieveGetter(Registries.BIOME)
			)
			.apply(i, NetherStripBiomeSource::new)
	);

	private final BiomeSource base;
	private final NetherLayout layout;
	private final Holder<Biome>[] rowBiomes;
	private final int bandMinQuart, shiftQuarts;

	public NetherStripBiomeSource(BiomeSource base, int layout, HolderGetter<Biome> biomes) {
		this.base = base;
		this.layout = NetherLayout.byId(layout);
		@SuppressWarnings("unchecked") Holder<Biome>[] rows = new Holder[this.layout.deepLayers + 1];
		for (int l = 1; l <= this.layout.deepLayers; l++) rows[l] = biomes.getOrThrow(this.layout.row(l).biome());
		this.rowBiomes = rows;
		this.bandMinQuart = QuartPos.fromBlock(this.layout.vanillaMinY());
		this.shiftQuarts = this.layout.frameOffset >> 2;
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

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		Stream.Builder<Holder<Biome>> rows = Stream.builder();
		for (int l = 1; l <= this.layout.deepLayers; l++) rows.add(this.rowBiomes[l]);
		return Stream.concat(this.base.possibleBiomes().stream(), rows.build()).distinct();
	}

	/** The deep layer's biome for a block y below the band, else null. The floors take the biome of the layer above them. */
	private Holder<Biome> deepBiome(int y) {
		if (y >= this.layout.vanillaMinY()) return null;
		int layer = this.layout.layerAt(y);
		if (layer < 1) {
			int floor = this.layout.floorAt(y);
			layer = floor >= 0 ? Math.max(1, floor) : this.layout.deepLayers;
		}
		return this.rowBiomes[layer];
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
		// only the band is sampled from the vanilla source, D_N blocks lower
		int lo = Math.max(minQuartY, this.bandMinQuart);
		int hi = Math.max(lo + 1, minQuartY + sizeY);
		int shift = this.shiftQuarts;
		BiomeResolver vanilla = this.base.createResolverForChunk(sampler, minQuartX, lo - shift, minQuartZ, sizeX, hi - lo, sizeZ);
		return (qx, qy, qz) -> {
			Holder<Biome> deep = this.deepBiome(QuartPos.toBlock(qy));
			return deep != null ? deep : vanilla.getNoiseBiome(qx, Math.clamp(qy, lo, hi - 1) - shift, qz);
		};
	}
}
