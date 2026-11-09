package dev.antcraft;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The floor guardians ({@link ArenaManager}): who they are, how strong, which advancement they
 * give and how their boss room is decorated. The enum constants are plain data (no game objects),
 * so layouts can refer to them before the game has bootstrapped; entity types, items and block
 * states are looked up when needed.
 */
public enum GuardianId {
	TUNNEL_CAPTAIN("Tunnel Captain", 60, 1.0, 0.6, "guardian_0", "antcraft.guardian.0"),
	DEEP_BEAST("Deep Beast", 180, 1.0, 0.6, "guardian_2", "antcraft.guardian.2"),
	CANYON_CONJURER("Canyon Conjurer", 160, 1.4, 0.6, "guardian_canyon_conjurer", "antcraft.guardian.canyon_conjurer"),
	BROOD_MOTHER("Brood Mother", 240, 2.2, 0.6, "guardian_brood_mother", "antcraft.guardian.brood_mother"),
	GALE_LORD("Gale Lord", 220, 1.8, 0.6, "guardian_gale_lord", "antcraft.guardian.gale_lord"),
	DESERT_COLOSSUS("Desert Colossus", 260, 2.5, 0.6, "guardian_6", "antcraft.guardian.6"),
	EMBER_LORD("Ember Lord", 400, 1.6, 0.8, "guardian_ember_lord", "antcraft.guardian.ember_lord"),
	HOGLIN_WARLORD("Hoglin Warlord", 240, 1.6, 0.6, "guardian_hoglin_warlord", "antcraft.guardian.hoglin_warlord"),
	MOLTEN_SOVEREIGN("Molten Sovereign", 300, 2.0, 0.6, "guardian_molten_sovereign", "antcraft.guardian.molten_sovereign"),
	BRUTE_WARLORD("Brute Warlord", 320, 1.5, 0.6, "guardian_brute_warlord", "antcraft.guardian.brute_warlord"),
	BONE_COLOSSUS("Bone Colossus", 280, 2.4, 0.8, "guardian_bone_colossus", "antcraft.guardian.bone_colossus");

	/** English name (fallback when the client has no translation). */
	public final String englishName;
	private final double health;
	public final double scale;
	public final double knockbackResistance;
	/** Advancement path ({@code antcraft:<id>}) granted for defeating it. */
	public final String advancementId;
	public final String langKey;

	GuardianId(String englishName, double health, double scale, double knockbackResistance, String advancementId, String langKey) {
		this.englishName = englishName;
		this.health = health;
		this.scale = scale;
		this.knockbackResistance = knockbackResistance;
		this.advancementId = advancementId;
		this.langKey = langKey;
	}

	/** Max health in a given overworld layout (layout 1's Desert Colossus is weaker). */
	public double health(int layoutId) {
		return this == DESERT_COLOSSUS && layoutId == 1 ? 200 : this.health;
	}

	public EntityType<? extends Mob> entityType() {
		return switch (this) {
			case TUNNEL_CAPTAIN -> EntityTypes.VINDICATOR;
			case DEEP_BEAST -> EntityTypes.RAVAGER;
			case CANYON_CONJURER -> EntityTypes.EVOKER;
			case BROOD_MOTHER -> EntityTypes.SPIDER;
			case GALE_LORD -> EntityTypes.BREEZE;
			case DESERT_COLOSSUS -> EntityTypes.HUSK;
			case EMBER_LORD -> EntityTypes.WITHER_SKELETON;
			case HOGLIN_WARLORD -> EntityTypes.HOGLIN;
			case MOLTEN_SOVEREIGN -> EntityTypes.MAGMA_CUBE;
			case BRUTE_WARLORD -> EntityTypes.PIGLIN_BRUTE;
			case BONE_COLOSSUS -> EntityTypes.SKELETON;
		};
	}

	/**
	 * Equipment and permanent effects, after the attributes are set. Layout 1's three guardians get
	 * exactly what they always had (Tunnel Captain: iron axe; Desert Colossus: Strength II). The
	 * others are filled in when their layouts generate them.
	 */
	public void outfit(Mob mob, int layoutId) {
		switch (this) {
			case TUNNEL_CAPTAIN -> {
				mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
				mob.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
			}
			case DESERT_COLOSSUS -> {
				mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, 1));
				if (layoutId >= 2) mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0));
			}
			default -> {}
		}
	}

	// ------------------------------------------------------------------ boss-room decoration

	/**
	 * Decoration of a boss room: pillars with an accent band, a carpet runner, wall banners,
	 * hanging lanterns and an accent at each pillar's foot; {@code candles} on the accents,
	 * {@code extraCobwebs} on pillar tops and corners.
	 */
	public record Theme(
		BlockState pillar, BlockState accentPillar, BlockState carpet, BlockState banner, BlockState lantern, BlockState accent,
		boolean candles, boolean extraCobwebs
	) {}

	private volatile Theme theme;

	public Theme theme() {
		Theme t = this.theme;
		if (t == null) this.theme = t = this.buildTheme();
		return t;
	}

	private Theme buildTheme() {
		return switch (this) {
			case TUNNEL_CAPTAIN -> theme(Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState(), DyeColor.RED, DyeColor.GRAY,
				Blocks.LANTERN.defaultBlockState(), Blocks.BARREL.defaultBlockState(), false, false);
			case DEEP_BEAST -> theme(Blocks.BONE_BLOCK.defaultBlockState(), Blocks.BONE_BLOCK.defaultBlockState(), DyeColor.BROWN, DyeColor.BROWN,
				Blocks.SOUL_LANTERN.defaultBlockState(), Blocks.SKELETON_SKULL.defaultBlockState(), false, false);
			case CANYON_CONJURER -> theme(Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE).defaultBlockState(), Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE).defaultBlockState(), DyeColor.YELLOW, DyeColor.RED,
				Blocks.LANTERN.defaultBlockState(), Blocks.POTTED_DEAD_BUSH.defaultBlockState(), false, false);
			case BROOD_MOTHER -> theme(Blocks.JUNGLE_LOG.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), DyeColor.GREEN, DyeColor.LIME,
				Blocks.LANTERN.defaultBlockState(), Blocks.COBWEB.defaultBlockState(), false, true);
			case GALE_LORD -> theme(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), DyeColor.LIGHT_GRAY, DyeColor.WHITE,
				Blocks.LANTERN.defaultBlockState(), Blocks.POTTED_AZURE_BLUET.defaultBlockState(), false, false);
			case DESERT_COLOSSUS -> theme(Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.CHISELED_SANDSTONE.defaultBlockState(), DyeColor.ORANGE, DyeColor.ORANGE,
				Blocks.LANTERN.defaultBlockState(), Blocks.DECORATED_POT.defaultBlockState(), true, false);
			case EMBER_LORD -> theme(Blocks.NETHER_BRICKS.defaultBlockState(), Blocks.RED_NETHER_BRICKS.defaultBlockState(), DyeColor.BLACK, DyeColor.RED,
				Blocks.SOUL_LANTERN.defaultBlockState(), Blocks.CAMPFIRE.defaultBlockState(), false, false);
			case HOGLIN_WARLORD -> theme(Blocks.CRIMSON_STEM.defaultBlockState(), Blocks.STRIPPED_CRIMSON_STEM.defaultBlockState(), DyeColor.RED, DyeColor.RED,
				Blocks.LANTERN.defaultBlockState(), Blocks.POTTED_CRIMSON_ROOTS.defaultBlockState(), false, false);
			case MOLTEN_SOVEREIGN -> theme(Blocks.POLISHED_BASALT.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), DyeColor.BLACK, DyeColor.ORANGE,
				Blocks.LANTERN.defaultBlockState(), Blocks.LAVA_CAULDRON.defaultBlockState(), false, false);
			case BRUTE_WARLORD -> theme(Blocks.GILDED_BLACKSTONE.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState(), DyeColor.YELLOW, DyeColor.BLACK,
				Blocks.LANTERN.defaultBlockState(), Blocks.PIGLIN_HEAD.defaultBlockState(), false, false);
			case BONE_COLOSSUS -> theme(Blocks.SMOOTH_BASALT.defaultBlockState(), Blocks.BONE_BLOCK.defaultBlockState(), DyeColor.LIGHT_BLUE, DyeColor.CYAN,
				Blocks.SOUL_LANTERN.defaultBlockState(), Blocks.WITHER_SKELETON_SKULL.defaultBlockState(), false, false);
		};
	}

	private static Theme theme(BlockState pillar, BlockState accentPillar, DyeColor carpet, DyeColor banner, BlockState lantern, BlockState accent, boolean candles, boolean extraCobwebs) {
		return new Theme(pillar, accentPillar, Blocks.CARPET.pick(carpet).defaultBlockState(), Blocks.WALL_BANNER.pick(banner).defaultBlockState(), lantern, accent, candles, extraCobwebs);
	}
}
