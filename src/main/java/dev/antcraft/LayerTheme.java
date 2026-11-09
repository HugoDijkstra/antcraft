package dev.antcraft;

import net.minecraft.network.chat.Component;

/**
 * What kind of deep layer a {@link LayerSpec} row is. A theme only carries the behaviour that used
 * to be a {@code layer == N} test, so every flag means the same in every layout; everything a
 * layout varies (stones, trees, plants, ...) lives in the row.
 */
public enum LayerTheme {
	// the original eight (layout 1's layers 1..8, in this order)
	OAK_HOLLOW("Oak Hollow", "layer_1", "antcraft.layer.1", 0),
	TAIGA_DEEP("Taiga Deep", "layer_2", "antcraft.layer.2", 0),
	DARKWOOD("Darkwood Caverns", "layer_3", "antcraft.layer.3", 0),
	JUNGLE("Jungle Grotto", "layer_4", "antcraft.layer.4", Flags.GLOW_BERRIES),
	DEEP_DARK("The Deep Dark", "layer_5", "antcraft.layer.5", Flags.CITY),
	BURIED_DESERT("Buried Desert", "layer_6", "antcraft.layer.6", Flags.DESERT),
	MANGROVE("Mangrove Mire", "layer_7", "antcraft.layer.7", Flags.SWAMP),
	MUSHROOM("Mushroom Abyss", "layer_8", "antcraft.layer.8", Flags.MUSHROOM),
	// tall worlds
	AUTUMN_HOLLOW("Autumn Hollow", "layer_autumn_hollow", "antcraft.layer.autumn_hollow", 0),
	LUSH_GROTTO("Lush Grotto", "layer_lush_grotto", "antcraft.layer.lush_grotto", Flags.GLOW_BERRIES),
	SULFUR_SPRINGS("Sulfur Springs", "layer_sulfur_springs", "antcraft.layer.sulfur_springs", 0),
	CRYSTAL_HOLLOWS("Crystal Hollows", "layer_crystal_hollows", "antcraft.layer.crystal_hollows", 0),
	FROZEN_DEPTHS("Frozen Depths", "layer_frozen_depths", "antcraft.layer.frozen_depths", 0),
	EMBER_RIFT("Ember Rift", "layer_ember_rift", "antcraft.layer.ember_rift", 0),
	OCHRE_BADLANDS("Ochre Badlands", "layer_ochre_badlands", "antcraft.layer.ochre_badlands", 0),
	TRIAL_VAULT("Trial Vault", "layer_trial_vault", "antcraft.layer.trial_vault", Flags.SOLID),
	PALE_HOLLOW("Pale Hollow", "layer_pale_hollow", "antcraft.layer.pale_hollow", 0),
	STONY_CRAGS("Stony Crags", "layer_stony_crags", "antcraft.layer.stony_crags", 0),
	SUNKEN_REEF("Sunken Reef", "layer_sunken_reef", "antcraft.layer.sunken_reef", Flags.WATER);

	private static final class Flags {
		static final int CITY = 1, DESERT = 2, SWAMP = 4, MUSHROOM = 8, WATER = 16, SOLID = 32, GLOW_BERRIES = 64;
	}

	public final String englishName;
	/** Advancement path ({@code antcraft:<id>}) for reaching a layer of this theme. */
	public final String advancementId;
	public final String langKey;
	private final int flags;

	LayerTheme(String englishName, String advancementId, String langKey, int flags) {
		this.englishName = englishName;
		this.advancementId = advancementId;
		this.langKey = langKey;
		this.flags = flags;
	}

	/** The ancient city lies in this layer's cave band. */
	public boolean hasCity() {
		return (this.flags & Flags.CITY) != 0;
	}

	/** Sand forest floor over sandstone, brighter "sky", desert wells, no noodles into the floor. */
	public boolean isDesert() {
		return (this.flags & Flags.DESERT) != 0;
	}

	/** Mud under the forest floor. */
	public boolean isSwamp() {
		return (this.flags & Flags.SWAMP) != 0;
	}

	/** No sugar cane at the ponds. */
	public boolean isMushroom() {
		return (this.flags & Flags.MUSHROOM) != 0;
	}

	/** The forest band is an underground sea. */
	public boolean isWater() {
		return (this.flags & Flags.WATER) != 0;
	}

	/** Solid layer (the Trial Vault): no caves, hall or forest. */
	public boolean isSolid() {
		return (this.flags & Flags.SOLID) != 0;
	}

	/** Glow berries hang from the forest ceiling. */
	public boolean ceilingGlowBerries() {
		return (this.flags & Flags.GLOW_BERRIES) != 0;
	}

	public Component displayName() {
		return Component.translatableWithFallback(this.langKey, this.englishName);
	}
}
