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
	/**
	 * Отдельное, медленное сглаживание для задержек отвода.
	 *
	 * Решение обновляется раз в 70–260 мс, и задержка прямого пути прыгает
	 * на весь скачок расстояния. Пройденный за 4 мс, такой скачок слышен как
	 * «чирп» доплера; за 60 мс он расползается в плавный сдвиг высоты.
	 */
	private float delaySmoothing;

	private float[] sendBus = new float[1024];
	private float wet = 0.6f;
	private float masterGain = 1f;

	// состояние слушателя, пишется с игрового потока
	public volatile double listenerX, listenerY, listenerZ;
	public volatile float listenerYaw, listenerPitch;
	public volatile Air air = new Air(20, 45, 101.325f);

	public Mixer() {
		aLow = onePole(XOVER_LOW);
		aHigh = onePole(XOVER_HIGH);
		smoothing = 1f - (float) Math.exp(-1.0 / (0.004 * SAMPLE_RATE));
		delaySmoothing = 1f - (float) Math.exp(-1.0 / (0.060 * SAMPLE_RATE));
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

	/** Сколько хвоста подмешивается сейчас. */
	public float wet() { return wet; }

	/**
	 * Настроить хвост по тому, что намерил решатель: время реверберации и
	 * средний свободный пробег приходят из статистики самих лучей.
	 */
	public void applyTail(float[] rt60, float meanFreePath) {
		applyTail(rt60, meanFreePath, 0f);
	}

	/**
	 * Настроить хвост по тому, что намерил решатель.
	 *
	 * Кроме времени затухания важно, насколько место вообще замкнуто: на берегу
	 * под открытым небом звуку неоткуда возвращаться, сколько бы камня вокруг ни
	 * было. Поэтому доля хвоста падает вместе с открытостью — иначе на пляже
	 * получается собор.
	 *
	 * @param openness 0 — глухая комната, 1 — чистое поле
	 */
	public void applyTail(float[] rt60, float meanFreePath, float openness) {
		if (rt60 == null || rt60.length < 3) return;
		float mid = rt60[2];
		float open = Math.max(0f, Math.min(1f, openness));
		// короткий хвост в открытом поле, длинный в камне — демпфирование по верхам
		float damping = 0.35f + 0.4f * Math.max(0f, Math.min(1f, 1f - mid / 3f));
		reverb.configure(mid, meanFreePath, air.speedOfSound, damping);
		setWet(Math.min(1.2f, 0.35f + mid * 0.5f) * (1f - 0.92f * open));
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
			s.mix(outL, outR, sendBus, frames, SAMPLE_RATE, aLow, aHigh, smoothing, delaySmoothing);
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

	/** Ниже этого уровня сигнал не трогаем совсем. */
	private static final float KNEE = 0.7f;

	/**
	 * Ограничение только на пиках.
	 *
	 * Прежняя кубическая кривая гнула сигнал на любой громкости: даже тихий
	 * звук получал несколько процентов гармоник и звучал грязно. Теперь до
	 * колена всё проходит один в один, а выше плавно поджимается к единице.
	 */
	private static float softClip(float x) {
		float a = Math.abs(x);
		if (a <= KNEE) return x;
		float over = (a - KNEE) / (1f - KNEE);
		float shaped = KNEE + (1f - KNEE) * (float) Math.tanh(over);
		return x < 0 ? -shaped : shaped;
	}
}
