package dev.d3sound.mc.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

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

	/** Слушатели событий звука: субтитры и чужие моды ждут оповещения. */
	@Accessor("listeners")
	java.util.List<SoundEventListener> d3sound$listeners();

	@Accessor("loaded")
	boolean d3sound$loaded();

	/** Громкость звука с учётом всех ползунков игры — она меняется на ходу. */
	@Invoker("calculateVolume")
	float d3sound$calculateVolume(SoundInstance instance);

	@Invoker("calculatePitch")
	float d3sound$calculatePitch(SoundInstance instance);
}
