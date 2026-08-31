package dev.d3sound.mc.client.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Внутренности звукового движка игры, которые нужны нашему:
 * библиотека распакованных звуков и пул каналов — через него мы получаем
 * собственный канал для готового бинаурального микса.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
	@Accessor("soundBuffers")
	SoundBufferLibrary d3sound$soundBuffers();

	@Accessor("channelAccess")
	ChannelAccess d3sound$channelAccess();

	@Accessor("loaded")
	boolean d3sound$loaded();
}
