package dev.d3sound.mc.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ядро движка: сводит все звучащие источники в один бинауральный поток.
 *
 * Разделение по потокам:
 *   • игровой поток считает физику (позиции, перекрытия, свойства помещения)
 *     и кладёт результат в целевые параметры источников;
 *   • аудиопоток только читает эти цели и плавно к ним идёт — там нет ни
 *     аллокаций, ни обращений к миру.
 */
public final class Mixer {
	public static final int SAMPLE_RATE = 48000;
	private static final float XOVER_LOW = 500f;
	private static final float XOVER_HIGH = 4000f;

	private final List<Source> sources = new CopyOnWriteArrayList<>();
	private final Fdn reverb = new Fdn(SAMPLE_RATE);

	private final float aLow;
	private final float aHigh;
	private float smoothing;

	private float[] sendBus = new float[1024];
	private float wet = 0.6f;
	private float masterGain = 1f;

	// состояние слушателя, пишется с игрового потока
	public volatile double listenerX, listenerY, listenerZ;
	public volatile float listenerYaw, listenerPitch;
	public volatile Air air = new Air(20, 45, 101.325f);
	public volatile VoxelAcoustics.Probe room;

	public Mixer() {
		aLow = onePole(XOVER_LOW);
		aHigh = onePole(XOVER_HIGH);
		smoothing = 1f - (float) Math.exp(-1.0 / (0.004 * SAMPLE_RATE));
	}

	private static float onePole(float cutoff) {
		float a = 1f - (float) Math.exp(-2 * Math.PI * cutoff / SAMPLE_RATE);
		return Math.max(1e-4f, Math.min(1f, a));
	}

	public void add(Source source) { sources.add(source); }

	public void remove(Source source) { sources.remove(source); }

	public List<Source> sources() { return sources; }

	public int activeCount() { return sources.size(); }

	public void setMasterGain(float gain) { this.masterGain = gain; }

	public void setWet(float wet) { this.wet = Math.max(0f, Math.min(2f, wet)); }

	/** Настроить хвост под текущее помещение. */
	public void applyRoom(VoxelAcoustics.Probe probe) {
		this.room = probe;
		if (probe == null) return;
		float damping = 0.35f + 0.4f * probe.openness;
		reverb.configure(probe.rt60[2], probe.meanFreePath, air.speedOfSound, damping);
		// на открытом воздухе отражать нечему
		setWet(0.9f * (1f - probe.openness * 0.9f));
	}

	/**
	 * Свести порцию. Вызывается из аудиопотока.
	 * Буферы должны быть длиной не меньше frames.
	 */
	public void render(float[] outL, float[] outR, int frames) {
		if (sendBus.length < frames) sendBus = new float[frames];
		java.util.Arrays.fill(outL, 0, frames, 0f);
		java.util.Arrays.fill(outR, 0, frames, 0f);
		java.util.Arrays.fill(sendBus, 0, frames, 0f);

		List<Source> done = null;
		for (Source s : sources) {
			s.mix(outL, outR, sendBus, frames, SAMPLE_RATE, aLow, aHigh, smoothing);
			if (s.finished) {
				if (done == null) done = new ArrayList<>(2);
				done.add(s);
			}
		}
		if (done != null) sources.removeAll(done);

		reverb.process(sendBus, outL, outR, frames, wet);

		for (int i = 0; i < frames; i++) {
			outL[i] = softClip(outL[i] * masterGain);
			outR[i] = softClip(outR[i] * masterGain);
		}
	}

	/** Мягкое ограничение — вместо жёсткого клиппинга на пиках. */
	private static float softClip(float x) {
		if (x > 1.4f) return 1f;
		if (x < -1.4f) return -1f;
		return x - (x * x * x) / 5.88f;
	}
}
