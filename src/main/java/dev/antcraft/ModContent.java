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

/**
 * AntCraft's own blocks and items: the seals on the floor holes (Nether Seal on floor 4, Ancient
 * Seal on floor 7, Guardian Seals on the boss-room floors 0, 2 and 6), the boss rooms' gates, and
 * the keys.
 */
public final class ModContent {
	private ModContent() {}

	public static final ResourceKey<Block> NETHER_SEAL_KEY = blockKey("nether_seal");
	public static final Block NETHER_SEAL = new SealBlock(
		unbreakable(NETHER_SEAL_KEY).mapColor(MapColor.NETHER).sound(SoundType.NETHER_BRICKS).lightLevel(state -> 9),
		() -> ModContent.NETHER_KEY,
		"opened_seal"
	);

	public static final ResourceKey<Block> ANCIENT_SEAL_KEY = blockKey("ancient_seal");
	public static final Block ANCIENT_SEAL = new SealBlock(
		unbreakable(ANCIENT_SEAL_KEY).mapColor(MapColor.COLOR_CYAN).sound(SoundType.SCULK_CATALYST).lightLevel(state -> 7),
		() -> ModContent.ANCIENT_KEY,
		"opened_ancient_seal"
	);

	public static final ResourceKey<Block> GUARDIAN_SEAL_KEY = blockKey("guardian_seal");
	public static final Block GUARDIAN_SEAL = new SealBlock(
		unbreakable(GUARDIAN_SEAL_KEY).mapColor(MapColor.COLOR_RED).sound(SoundType.POLISHED_DEEPSLATE).lightLevel(state -> 9),
		null,
		null
	);

	/** Closes the boss rooms' doorways during a fight. */
	public static final ResourceKey<Block> ARENA_GATE_KEY = blockKey("arena_gate");
	public static final Block ARENA_GATE = new Block(
		unbreakable(ARENA_GATE_KEY).mapColor(MapColor.COLOR_BLACK).sound(SoundType.NETHERITE_BLOCK).lightLevel(state -> 4)
	);

	/** Boss-room teleporter: lit once the room's guardian is defeated, takes you to the room above. */
	public static final ResourceKey<Block> TELEPORTER_KEY = blockKey("teleporter");
	public static final Block TELEPORTER = new TeleporterBlock(
		unbreakable(TELEPORTER_KEY).mapColor(MapColor.COLOR_PURPLE).sound(SoundType.AMETHYST).lightLevel(state -> state.getValue(TeleporterBlock.LIT) ? 13 : 3)
	);

	public static final Item NETHER_SEAL_ITEM = blockItem(NETHER_SEAL_KEY, NETHER_SEAL);
	public static final Item ANCIENT_SEAL_ITEM = blockItem(ANCIENT_SEAL_KEY, ANCIENT_SEAL);
	public static final Item GUARDIAN_SEAL_ITEM = blockItem(GUARDIAN_SEAL_KEY, GUARDIAN_SEAL);
	public static final Item ARENA_GATE_ITEM = blockItem(ARENA_GATE_KEY, ARENA_GATE);
	public static final Item TELEPORTER_ITEM = blockItem(TELEPORTER_KEY, TELEPORTER);

	public static final ResourceKey<Item> NETHER_KEY_KEY = ResourceKey.create(Registries.ITEM, id("nether_key"));
	public static final Item NETHER_KEY = new Item(new Item.Properties().setId(NETHER_KEY_KEY).stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	public static final ResourceKey<Item> ANCIENT_KEY_KEY = ResourceKey.create(Registries.ITEM, id("ancient_key"));
	public static final Item ANCIENT_KEY = new Item(new Item.Properties().setId(ANCIENT_KEY_KEY).stacksTo(1).rarity(Rarity.EPIC).fireResistant());

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(AntCraft.MOD_ID, path);
	}

	private static ResourceKey<Block> blockKey(String path) {
		return ResourceKey.create(Registries.BLOCK, id(path));
	}

	/** Like bedrock: can't be mined, blown up or pushed, and drops nothing. */
	private static BlockBehaviour.Properties unbreakable(ResourceKey<Block> key) {
		return BlockBehaviour.Properties.of().setId(key).strength(-1.0F, 3600000.0F).noLootTable();
	}

	private static Item blockItem(ResourceKey<Block> blockKey, Block block) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, blockKey.identifier());
		return new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix());
	}

	public static void register() {
		Registry.register(BuiltInRegistries.BLOCK, NETHER_SEAL_KEY, NETHER_SEAL);
		Registry.register(BuiltInRegistries.BLOCK, ANCIENT_SEAL_KEY, ANCIENT_SEAL);
		Registry.register(BuiltInRegistries.BLOCK, GUARDIAN_SEAL_KEY, GUARDIAN_SEAL);
		Registry.register(BuiltInRegistries.BLOCK, ARENA_GATE_KEY, ARENA_GATE);
		Registry.register(BuiltInRegistries.BLOCK, TELEPORTER_KEY, TELEPORTER);
		registerBlockItem(NETHER_SEAL_KEY, NETHER_SEAL_ITEM);
		registerBlockItem(ANCIENT_SEAL_KEY, ANCIENT_SEAL_ITEM);
		registerBlockItem(GUARDIAN_SEAL_KEY, GUARDIAN_SEAL_ITEM);
		registerBlockItem(ARENA_GATE_KEY, ARENA_GATE_ITEM);
		registerBlockItem(TELEPORTER_KEY, TELEPORTER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, NETHER_KEY_KEY, NETHER_KEY);
		Registry.register(BuiltInRegistries.ITEM, ANCIENT_KEY_KEY, ANCIENT_KEY);
	}

	private static void registerBlockItem(ResourceKey<Block> blockKey, Item item) {
		Registry.register(BuiltInRegistries.ITEM, ResourceKey.create(Registries.ITEM, blockKey.identifier()), item);
	}
}
