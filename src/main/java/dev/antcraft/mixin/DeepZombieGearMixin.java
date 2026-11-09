package dev.antcraft.mixin;

import dev.antcraft.AntCraft;
import dev.antcraft.Strip;
import dev.antcraft.StripLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.providers.VanillaEnchantmentProviders;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zombies (also husks, drowned and zombie villagers) spawning in the deep layers come better
 * equipped the deeper they are: more armour pieces, better materials (chainmail in layers 1-2 up to
 * diamond and netherite in 7-8), a weapon more often, and more often enchanted. Spawn eggs and
 * /summon stay vanilla.
 */
@Mixin(Zombie.class)
public abstract class DeepZombieGearMixin {
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final Item[][] ARMOR_TIERS = {
		{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
		{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
		{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
		{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS},
	};
	private static final Item[][] WEAPON_TIERS = {
		{Items.STONE_SWORD, Items.IRON_SHOVEL},
		{Items.IRON_SWORD, Items.IRON_AXE},
		{Items.DIAMOND_SWORD, Items.IRON_AXE},
		{Items.DIAMOND_SWORD, Items.NETHERITE_SWORD},
	};

	@Inject(method = "finalizeSpawn", at = @At("TAIL"))
	private void antcraft$deepGear(
		ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, @Nullable SpawnGroupData groupData,
		CallbackInfoReturnable<SpawnGroupData> cir
	) {
		if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.SPAWNER && reason != EntitySpawnReason.REINFORCEMENT) return;
		Zombie self = (Zombie) (Object) this;
		ServerLevel server = level.getLevel();
		if (!AntCraft.isDeepFarm(server, self.blockPosition())) return;
		StripLayout layout = Strip.layout(server);
		if (layout == null) return;
		int deep = layout.layerAt(self.getBlockY());
		if (deep < 1) return;
		RandomSource random = level.getRandom();
		int baseTier = (deep - 1) * 4 / layout.deepLayers; // 0..3
		int layer = layout.scaledLayer(deep); // chances as in layout 1's 8 layers
		float pieceChance = Math.min(1.0F, 0.2F + 0.1F * layer);
		float enchantChance = 0.1F * layer;
		for (int i = 0; i < ARMOR.length; i++) {
			if (random.nextFloat() >= pieceChance) continue;
			int tier = Math.max(0, baseTier - (random.nextFloat() < 0.35F ? 1 : 0)); // some pieces a tier lower
			equip(self, server, ARMOR[i], ARMOR_TIERS[tier][i], random, enchantChance, difficulty);
		}
		if (random.nextFloat() < 0.3F + 0.08F * layer) {
			Item[] weapons = WEAPON_TIERS[baseTier];
			equip(self, server, EquipmentSlot.MAINHAND, weapons[random.nextInt(weapons.length)], random, enchantChance, difficulty);
		}
	}

	private static void equip(Zombie zombie, ServerLevel level, EquipmentSlot slot, Item item, RandomSource random, float enchantChance, DifficultyInstance difficulty) {
		ItemStack stack = new ItemStack(item);
		if (random.nextFloat() < enchantChance) {
			EnchantmentHelper.enchantItemFromProvider(stack, level.registryAccess(), VanillaEnchantmentProviders.MOB_SPAWN_EQUIPMENT, difficulty, random);
		}
		zombie.setItemSlot(slot, stack);
	}
}
