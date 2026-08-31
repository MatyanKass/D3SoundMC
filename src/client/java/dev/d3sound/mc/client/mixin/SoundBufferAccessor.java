package dev.d3sound.mc.client.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Доступ к распакованным семплам.
 *
 * Игра держит их в приватном поле и обнуляет, как только загрузит в OpenAL.
 * Мы забираем звуки до этого момента — свой микшер работает с сырым PCM.
 */
@Mixin(SoundBuffer.class)
public interface SoundBufferAccessor {
	@Accessor("data")
	@Nullable ByteBuffer d3sound$data();
}
