package dev.d3sound.mc.client.mixin;

import dev.d3sound.mc.client.gui.D3OptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
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
	}
}
