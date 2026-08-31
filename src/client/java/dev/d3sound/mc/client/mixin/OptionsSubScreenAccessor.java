package dev.d3sound.mc.client.mixin;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к списку настроек — он объявлен в базовом экране. */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {
	@Accessor("list")
	OptionsList d3sound$list();
}
