package dev.d3sound.mc.client.mixin;

import dev.d3sound.mc.client.D3Config;
import dev.d3sound.mc.client.gui.D3OptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Кнопка входа в настройки движка прямо в стандартных настройках звука. */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin {
	@Inject(method = "addOptions", at = @At("TAIL"))
	private void d3sound$addButton(CallbackInfo info) {
		OptionsList list = ((OptionsSubScreenAccessor) this).d3sound$list();
		if (list == null) return;
		SoundOptionsScreen self = (SoundOptionsScreen) (Object) this;
		Button button = Button.builder(Component.translatable("d3sound.options.open"),
			b -> Minecraft.getInstance().setScreenAndShow(new D3OptionsScreen(self, Minecraft.getInstance().options))).build();
		list.addSmall(button, null);
		if (D3Config.get().enabled) lockVanillaAudio(list, Minecraft.getInstance().options);
	}

	/**
	 * Пока звук считаем мы, встроенные переключатели движка игры ни на что не
	 * влияют: панорама, стереобаза и прочее берутся из нашей модели головы.
	 * Поэтому их видно, но трогать нельзя — чтобы не искать потом, почему
	 * галочка стоит, а слышно то же самое.
	 */
	private static void lockVanillaAudio(OptionsList list, Options options) {
		Tooltip reason = Tooltip.create(Component.translatable("d3sound.options.locked"));
		AbstractWidget widget = list.findOption(options.directionalAudio());
		if (widget != null) {
			widget.active = false;
			widget.setTooltip(reason);
		}
	}
}
