package dev.antcraft;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * The Void Seal: the two-high door of the Crown Vault at the top of the tall End. The Void Key
 * opens it into a doorway (the whole door, not a ladder).
 */
public class VoidSealBlock extends SealBlock {
	public VoidSealBlock(Properties properties, @Nullable Supplier<Item> key, @Nullable String advancement) {
		super(properties, key, advancement);
	}

	/**
	 * Until the Void Seal is opened, players outside creative cannot break the Crown Vault's shell
	 * (the seal wall and the walls, floor and roof behind it), so the vault is only entered through
	 * the seal. Server side, tall End only.
	 */
	public static void registerVaultGuard() {
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (player.isCreative() || EndStripChunkGenerator.tallEnd(level) == null) return true;
			StripBounds bounds = EndStripChunkGenerator.endBounds(level);
			if (bounds == null || !inVaultShell(bounds, pos)) return true;
			return !level.getBlockState(EndLayout.voidSeal(bounds)).is(ModContent.VOID_SEAL);
		});
	}

	/** The Crown Vault with its walls: the seal wall's z to the Crown's end, floor to roof, the strip's width. */
	static boolean inVaultShell(StripBounds bounds, BlockPos pos) {
		return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
			&& pos.getY() >= EndLayout.CROWN_FLOOR_Y && pos.getY() <= EndLayout.CROWN_ROOF_Y
			&& pos.getZ() >= EndLayout.CROWN_SEAL_Z && pos.getZ() <= EndLayout.CROWN_MAX_Z;
	}

	@Override
	protected void openWithKey(Level level, BlockPos pos) {
		BlockPos bottom = pos;
		while (level.getBlockState(bottom.below()).is(this)) bottom = bottom.below();
		for (BlockPos p = bottom; level.getBlockState(p).is(this); p = p.above()) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.6F);
		level.playSound(null, pos, SoundEvents.END_GATEWAY_SPAWN, SoundSource.BLOCKS, 0.6F, 1.0F);
	}
}
