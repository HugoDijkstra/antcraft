package dev.antcraft;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** AntCraft's own block and item: the Nether Seal on floor 4's hole and the Nether Key that opens it. */
public final class ModContent {
	private ModContent() {}

	public static final ResourceKey<Block> NETHER_SEAL_KEY = ResourceKey.create(Registries.BLOCK, id("nether_seal"));
	public static final Block NETHER_SEAL = new NetherSealBlock(
		BlockBehaviour.Properties.of()
			.setId(NETHER_SEAL_KEY)
			.mapColor(MapColor.NETHER)
			.strength(-1.0F, 3600000.0F) // unbreakable, like bedrock
			.noLootTable()
			.sound(SoundType.NETHER_BRICKS)
			.lightLevel(state -> 9)
	);

	public static final ResourceKey<Item> NETHER_SEAL_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("nether_seal"));
	public static final Item NETHER_SEAL_ITEM = new BlockItem(NETHER_SEAL, new Item.Properties().setId(NETHER_SEAL_ITEM_KEY).useBlockDescriptionPrefix());

	public static final ResourceKey<Item> NETHER_KEY_KEY = ResourceKey.create(Registries.ITEM, id("nether_key"));
	public static final Item NETHER_KEY = new Item(new Item.Properties().setId(NETHER_KEY_KEY).stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, path);
	}

	public static void register() {
		Registry.register(BuiltInRegistries.BLOCK, NETHER_SEAL_KEY, NETHER_SEAL);
		Registry.register(BuiltInRegistries.ITEM, NETHER_SEAL_ITEM_KEY, NETHER_SEAL_ITEM);
		Registry.register(BuiltInRegistries.ITEM, NETHER_KEY_KEY, NETHER_KEY);
	}
}
