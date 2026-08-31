package dev.d3sound.mc.client.gui;

import dev.d3sound.mc.client.Conflicts;
import dev.d3sound.mc.client.D3Config;
import dev.d3sound.mc.client.D3SoundEngine;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Экран настроек движка — открывается из стандартных настроек звука.
 *
 * У каждого пункта есть пояснение при наведении: за что он отвечает и чем
 * обернётся его изменение. Ползунок в крайнем левом положении там, где это
 * имеет смысл, означает «Авто» — значение подберёт сам движок.
 */
public final class D3OptionsScreen extends OptionsSubScreen {
	private final D3Config config = D3Config.get();

	public D3OptionsScreen(final Screen lastScreen, final Options options) {
		super(lastScreen, options, Component.translatable("d3sound.options.title"));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;

		this.list.addBig(toggle("d3sound.options.enabled", config.enabled, value -> {
			config.enabled = value;
			D3SoundEngine engine = D3SoundEngine.get();
			engine.enabled = value;
			if (!value) engine.stopAll();
		}));

		this.list.addSmall(
			percent("d3sound.options.share", 2, 100, config.cpuShare, value -> config.cpuShare = value, false),
			percent("d3sound.options.headroom", 40, 100, config.cpuHeadroom, value -> config.cpuHeadroom = value, false),
			percent("d3sound.options.quality", 0, 100, config.quality, value -> config.quality = value, true),
			percent("d3sound.options.gain", 0, 500, config.gain, value -> config.gain = value, false),
			toggle("d3sound.options.diffraction", config.diffraction, value -> config.diffraction = value),
			toggle("d3sound.options.reflections", config.reflections, value -> config.reflections = value),
			toggle("d3sound.options.structure", config.structure, value -> config.structure = value),
			percent("d3sound.options.structure_level", 0, 300, config.structureLevel, value -> config.structureLevel = value, false),
			percent("d3sound.options.reverb", 0, 200, config.reverb, value -> config.reverb = value, true),
			percent("d3sound.options.doppler", 0, 200, config.doppler, value -> config.doppler = value, false),
			toggle("d3sound.options.overlay", config.overlay, value -> config.overlay = value)
		);

		List<Conflicts.Found> conflicts = Conflicts.result();
		this.list.addSmall(Button.builder(
			conflicts == null
				? Component.translatable("d3sound.options.conflicts.checking")
				: Component.translatable("d3sound.options.conflicts", conflicts.size()),
			b -> D3ConflictsScreen.open(this)).build(), null);
	}

	/** Переключатель с пояснением: ключ подсказки — это ключ пункта плюс {@code .tip}. */
	private static OptionInstance<Boolean> toggle(String key, boolean current, java.util.function.Consumer<Boolean> sink) {
		return OptionInstance.createBoolean(key, tip(key), current, sink::accept);
	}

	/** Ползунок в процентах; при autoAtZero крайнее левое значение — «Авто». */
	private static OptionInstance<Integer> percent(String key, int min, int max, int current,
	                                               IntConsumer sink, boolean autoAtZero) {
		return new OptionInstance<>(
			key,
			tip(key),
			(caption, value) -> autoAtZero && value == 0
				? Component.translatable("d3sound.options.value.auto", caption)
				: Component.translatable("d3sound.options.value.percent", caption, value),
			new OptionInstance.IntRange(min, max),
			Math.max(min, Math.min(max, current)),
			sink::accept);
	}

	private static <T> OptionInstance.TooltipSupplier<T> tip(String key) {
		return OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tip"));
	}

	@Override
	public void removed() {
		config.save();
		super.removed();
	}
}
