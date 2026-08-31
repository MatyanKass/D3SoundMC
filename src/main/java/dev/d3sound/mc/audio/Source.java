package dev.d3sound.mc.audio;

/**
 * Один звучащий источник.
 *
 * Курсор идёт по сэмплам исходного звука, а каждое ухо читает из буфера
 * со своей задержкой — распространение и межушная разница получаются сами
 * собой: пока звук «не долетел», ухо читает то, чего ещё нет, и молчит.
 * Оттуда же берётся доплер: задержка плавно меняется вместе с расстоянием.
 *
 * Спектр задан тремя полосами (раздел 500 Гц и 4 кГц) — столько фильтров
 * можно позволить на источник в реальном времени.
 */
public final class Source {
	public final String name;
	public final float[] pcm;
	public final int sampleRate;
	public final boolean loop;
	public final boolean relative;      // привязан к слушателю: интерфейс, музыка

	public volatile double x, y, z;
	public volatile float volume = 1f;
	public volatile float pitch = 1f;
	public volatile boolean stopping;

	/** Целевые параметры, их считает физика на игровом потоке. */
	public volatile float targetDelayLeft, targetDelayRight;
	public final float[] targetGainLeft = new float[3];
	public final float[] targetGainRight = new float[3];
	public volatile float targetSend;

	/** Текущие сглаженные значения — только для аудиопотока. */
	private float delayLeft, delayRight;
	private final float[] gainLeft = new float[3];
	private final float[] gainRight = new float[3];
	private float send;
	private boolean primed;

	private double cursor;
	private float loL, miL, loR, miR;
	public boolean finished;

	public Source(String name, float[] pcm, int sampleRate, boolean loop, boolean relative) {
		this.name = name;
		this.pcm = pcm;
		this.sampleRate = sampleRate;
		this.loop = loop;
		this.relative = relative;
	}

	public double cursorSeconds() { return cursor / sampleRate; }

	/**
	 * Смешать порцию в выходные буферы.
	 *
	 * @param aLow  коэффициент однополюсника нижнего раздела
	 * @param aHigh коэффициент верхнего раздела
	 * @param kSmooth скорость сглаживания параметров
	 */
	public void mix(float[] outL, float[] outR, float[] sendBus, int frames,
	                int outRate, float aLow, float aHigh, float kSmooth) {
		if (finished) return;
		if (!primed) {
			delayLeft = targetDelayLeft;
			delayRight = targetDelayRight;
			System.arraycopy(targetGainLeft, 0, gainLeft, 0, 3);
			System.arraycopy(targetGainRight, 0, gainRight, 0, 3);
			send = targetSend;
			primed = true;
		}

		final double rate = (double) sampleRate / outRate * Math.max(0.05f, pitch);
		final float dlTarget = targetDelayLeft * sampleRate;
		final float drTarget = targetDelayRight * sampleRate;

		for (int i = 0; i < frames; i++) {
			delayLeft += (dlTarget - delayLeft) * kSmooth;
			delayRight += (drTarget - delayRight) * kSmooth;
			for (int b = 0; b < 3; b++) {
				gainLeft[b] += (targetGainLeft[b] - gainLeft[b]) * kSmooth;
				gainRight[b] += (targetGainRight[b] - gainRight[b]) * kSmooth;
			}
			send += (targetSend - send) * kSmooth;

			float sl = sample(cursor - delayLeft);
			float sr = sample(cursor - delayRight);

			// три полосы двумя однополюсниками
			loL += aLow * (sl - loL);
			float restL = sl - loL;
			miL += aHigh * (restL - miL);
			float hiL = restL - miL;
			outL[i] += gainLeft[0] * loL + gainLeft[1] * miL + gainLeft[2] * hiL;

			loR += aLow * (sr - loR);
			float restR = sr - loR;
			miR += aHigh * (restR - miR);
			float hiR = restR - miR;
			outR[i] += gainRight[0] * loR + gainRight[1] * miR + gainRight[2] * hiR;

			// в реверберацию уходит сухой сигнал в точке излучения
			if (sendBus != null) sendBus[i] += sample(cursor) * send;

			cursor += rate;
			if (cursor >= pcm.length) {
				if (loop) cursor -= pcm.length;
				else if (cursor - delayRight > pcm.length + sampleRate * 0.5) { finished = true; return; }
			}
		}
		if (stopping) finished = true;
	}

	/** Чтение с дробной позицией (линейная интерполяция). */
	private float sample(double index) {
		if (index < 0) return 0f;               // звук ещё не долетел
		if (index >= pcm.length - 1) {
			if (!loop) return 0f;
			index = index % pcm.length;
		}
		int i0 = (int) index;
		float frac = (float) (index - i0);
		int i1 = i0 + 1 < pcm.length ? i0 + 1 : (loop ? 0 : i0);
		return pcm[i0] + (pcm[i1] - pcm[i0]) * frac;
	}
}
