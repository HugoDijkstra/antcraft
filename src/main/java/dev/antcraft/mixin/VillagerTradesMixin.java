package dev.antcraft.mixin;

import dev.antcraft.Strip;
import dev.antcraft.StripChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villagers deep in the strip trade better: a villager working in deep layer L starts its career at
 * level 1 + ceil(L / 2) (layers 1-2 Apprentice, 3-4 Journeyman, 5-6 Expert, 7-8 Master), so it
 * offers the higher-tier trades of its profession straight away. Like vanilla's "unlock all trades"
 * debug option, each level-up adds that level's offers and the next one follows from there.
 */
@Mixin(Villager.class)
public abstract class VillagerTradesMixin {
	@Shadow
	public abstract VillagerData getVillagerData();

	@Shadow
	public abstract void setVillagerData(VillagerData data);

	@Shadow
	public abstract int getVillagerXp();

	@Shadow
	public abstract void setVillagerXp(int value);

	@Shadow
	protected abstract void updateTrades(ServerLevel level);

	@Inject(method = "updateTrades", at = @At("TAIL"))
	private void antcraft$deepVillagersTradeBetter(ServerLevel level, CallbackInfo ci) {
		if (level != level.getServer().overworld() || !(level.getChunkSource().getGenerator() instanceof StripChunkGenerator)) return;
		Villager self = (Villager) (Object) this;
		int y = self.getBlockY();
		if (y >= Strip.VANILLA_MIN_Y) return;
		int layer = Strip.layerAt(y);
		if (layer < 1) return;
		int target = Math.min(5, 1 + (layer + 1) / 2);
		VillagerData data = this.getVillagerData();
		int current = data.level();
		if (current >= target || data.profession().value().getTrades(current) == null) return;
		this.setVillagerData(data.withLevel(current + 1));
		if (this.getVillagerXp() < VillagerData.getMinXpPerLevel(current + 1)) this.setVillagerXp(VillagerData.getMinXpPerLevel(current + 1));
		this.updateTrades(level); // adds the next level's offers, and recurses up to the target
	}
}
