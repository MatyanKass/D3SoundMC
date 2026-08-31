package dev.d3sound.mc.client;

import dev.d3sound.mc.audio.Mixer;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Мост между нашим микшером и звуковым каналом игры.
 *
 * Minecraft умеет проигрывать произвольный поток, поэтому весь наш бинауральный
 * микс уходит в один канал — игра лишь переносит байты в OpenAL, всё остальное
 * считаем мы.
 *
 * Порции короткие: движок по умолчанию берёт буферы по секунде, а это секунды
 * задержки. Мы отдаём десятки миллисекунд и сами чаще подкачиваем канал.
 */
public final class D3Stream implements AudioStream {
	/** Длина одной порции. Компромисс между задержкой и риском недокачки. */
	public static final float CHUNK_SECONDS = 0.03f;

	private final Mixer mixer;
	private final AudioFormat format;
	private final float[] left;
	private final float[] right;
	private final int chunkFrames;

	public D3Stream(Mixer mixer) {
		this.mixer = mixer;
		this.format = new AudioFormat(Mixer.SAMPLE_RATE, 16, 2, true, false);
		this.chunkFrames = Math.round(Mixer.SAMPLE_RATE * CHUNK_SECONDS);
		this.left = new float[chunkFrames];
		this.right = new float[chunkFrames];
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(final int expectedSize) {
		int frames = Math.min(chunkFrames, Math.max(64, expectedSize / 4));
		mixer.render(left, right, frames);

		ByteBuffer out = BufferUtils.createByteBuffer(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < frames; i++) {
			out.putShort(toPcm(left[i]));
			out.putShort(toPcm(right[i]));
		}
		out.flip();
		return out;
	}

	private static short toPcm(float v) {
		int s = Math.round(v * 32767f);
		if (s > 32767) s = 32767;
		if (s < -32768) s = -32768;
		return (short) s;
	}

	@Override
	public void close() {
		// поток бесконечный: закрывать нечего
	}
}
