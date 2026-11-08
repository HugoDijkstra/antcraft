package dev.antcraft.client;

import dev.antcraft.AntCraftConfig;
import dev.antcraft.StripBounds;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * AntCraft's settings, opened from Mod Menu. A plain vanilla screen: sliders for the numbers,
 * on/off buttons for the rest. Changes are written to {@code config/antcraft.json} on Done and
 * thrown away on Cancel / Escape.
 */
public final class AntCraftConfigScreen extends Screen {
	private static final int COLUMN = 150, GAP = 10, ROW = 24;

	private final @Nullable Screen parent;
	private final AntCraftConfig edited = AntCraftConfig.get().copy();
	private int sizeLabelY;

	public AntCraftConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("antcraft.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - COLUMN - GAP / 2;
		int right = this.width / 2 + GAP / 2;
		int y = 40;

		this.sizeLabelY = y;
		y += 14;
		AntCraftConfig c = this.edited;
		this.addRenderableWidget(new RangeSlider(
			left, y, Component.translatable("antcraft.config.strip_width"), StripBounds.MIN_WIDTH, StripBounds.MAX_WIDTH, 1, c.stripWidth,
			v -> Component.literal(Integer.toString((int) v)), v -> c.stripWidth = (int) v
		));
		this.addRenderableWidget(new RangeSlider(
			right, y, Component.translatable("antcraft.config.strip_length"), StripBounds.MIN_LENGTH, StripBounds.MAX_LENGTH, 10, c.stripLength,
			v -> Component.literal(Integer.toString((int) v)), v -> c.stripLength = (int) v
		));
		y += ROW + 12;

		RangeSlider hardness = new RangeSlider(
			left, y, Component.translatable("antcraft.config.mining_hardness"), 0.0, AntCraftConfig.MAX_HARDNESS_PER_LAYER, 0.1, c.miningHardnessPerLayer,
			v -> v <= 0.0 ? Component.translatable("antcraft.config.mining_hardness.off")
				: Component.translatable("antcraft.config.mining_hardness.value", String.format(Locale.ROOT, "%.1f", v)),
			v -> c.miningHardnessPerLayer = v
		);
		hardness.setTooltip(Tooltip.create(Component.translatable("antcraft.config.mining_hardness.tooltip")));
		this.addRenderableWidget(hardness);
		this.addRenderableWidget(toggle(right, y, "layer_titles", c.layerTitles, v -> c.layerTitles = v));
		y += ROW;
		this.addRenderableWidget(toggle(left, y, "spawn_mobs_near_players", c.spawnMobsNearPlayers, v -> c.spawnMobsNearPlayers = v));
		CycleButton<Boolean> guardians = toggle(right, y, "guardians", c.guardians, v -> c.guardians = v);
		guardians.setTooltip(Tooltip.create(Component.translatable("antcraft.config.guardians.tooltip")));
		this.addRenderableWidget(guardians);
		y += ROW;
		CycleButton<Boolean> netherGate = toggle(left, y, "nether_key_gate", c.netherKeyGate, v -> c.netherKeyGate = v);
		netherGate.setTooltip(Tooltip.create(Component.translatable("antcraft.config.nether_key_gate.tooltip")));
		this.addRenderableWidget(netherGate);
		CycleButton<Boolean> ancientGate = toggle(right, y, "ancient_key_gate", c.ancientKeyGate, v -> c.ancientKeyGate = v);
		ancientGate.setTooltip(Tooltip.create(Component.translatable("antcraft.config.ancient_key_gate.tooltip")));
		this.addRenderableWidget(ancientGate);

		int bottom = this.height - 28;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
			this.edited.apply();
			this.onClose();
		}).bounds(left, bottom, COLUMN, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(right, bottom, COLUMN, 20).build());
	}

	private interface BoolSetter {
		void set(boolean value);
	}

	private static CycleButton<Boolean> toggle(int x, int y, String key, boolean initial, BoolSetter setter) {
		return CycleButton.onOffBuilder(initial)
			.create(x, y, COLUMN, 20, Component.translatable("antcraft.config." + key), (button, value) -> setter.set(value));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 15, -1);
		graphics.centeredText(this.font, Component.translatable("antcraft.config.new_worlds"), this.width / 2, this.sizeLabelY, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** A slider over [min, max] that snaps to {@code step}. */
	private static final class RangeSlider extends AbstractSliderButton {
		private final Component name;
		private final double min, max, step;
		private final DoubleFunction<Component> format;
		private final DoubleConsumer setter;

		RangeSlider(int x, int y, Component name, double min, double max, double step, double initial, DoubleFunction<Component> format, DoubleConsumer setter) {
			super(x, y, COLUMN, 20, Component.empty(), (Mth.clamp(initial, min, max) - min) / (max - min));
			this.name = name;
			this.min = min;
			this.max = max;
			this.step = step;
			this.format = format;
			this.setter = setter;
			this.updateMessage();
		}

		private double current() {
			double raw = this.min + this.value * (this.max - this.min);
			double snapped = this.min + Math.round((raw - this.min) / this.step) * this.step;
			return Mth.clamp(Math.round(snapped * 1000.0) / 1000.0, this.min, this.max);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(CommonComponents.optionNameValue(this.name, this.format.apply(this.current())));
		}

		@Override
		protected void applyValue() {
			this.setter.accept(this.current());
		}
	}
}
