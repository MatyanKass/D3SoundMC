package dev.d3sound.mc.audio;

/**
 * Один звучащий источник и все пути, которыми его звук доходит до ушей.
 *
 * Каждый путь — отдельный отвод: своя задержка на каждое ухо и свой спектр.
 * Первый отвод это прямой звук (или обходной, если прямой перекрыт), остальные —
 * отражения. Курсор идёт по семплам исходного звука, а отводы читают из буфера
 * назад по времени, поэтому задержка распространения, эхо и доплер получаются
 * из самой геометрии.
 */
public final class Source {
	public static final int MAX_TAPS = Solution.MAX_TAPS;

	/** Один путь звука. */
	public static final class Tap {
		public volatile boolean active;
		public volatile float targetDelayLeft, targetDelayRight;
		public final float[] targetGainLeft = new float[3];
		public final float[] targetGainRight = new float[3];

		float delayLeft, delayRight;
		final float[] gainLeft = new float[3];
		final float[] gainRight = new float[3];
		float loL, miL, loR, miR;
		boolean primed;

		/**
		 * Отвод включается заново: его прошлая задержка уже не имеет смысла.
		 * Без этого путь «прыгает» со старого расстояния на новое и щёлкает.
		 */
		public void arm() { primed = false; }

		void prime() {
			delayLeft = targetDelayLeft;
			delayRight = targetDelayRight;
			for (int i = 0; i < 3; i++) { gainLeft[i] = 0; gainRight[i] = 0; }
			primed = true;
		}
	}

	public final String name;
	public final float[] pcm;
	public final int sampleRate;
	public final boolean loop;
	public final boolean relative;
	public final long id;

	public volatile double x, y, z;
	public volatile float volume = 1f;
	public volatile float pitch = 1f;
	public volatile boolean stopping;
	/** Удар по блоку: шаг, кирка, поршень, взрыв — они бьют по конструкции. */
	public volatile boolean impact;
	public volatile float targetSend;

	public final Tap[] taps = new Tap[MAX_TAPS];
	public volatile int tapCount = 1;

	private float send;
	private double cursor;
	public boolean finished;

	public Source(long id, String name, float[] pcm, int sampleRate, boolean loop, boolean relative) {
		this.id = id;
		this.name = name;
		this.pcm = pcm;
		this.sampleRate = sampleRate;
		this.loop = loop;
		this.relative = relative;
		for (int i = 0; i < MAX_TAPS; i++) taps[i] = new Tap();
	}

	public double lengthSeconds() { return (double) pcm.length / sampleRate; }

	/**
	 * Смешать порцию.
	 *
	 * @param aLow    коэффициент нижнего раздела полос
	 * @param aHigh   коэффициент верхнего раздела
	 * @param kSmooth скорость сглаживания параметров
	 */
	public void mix(float[] outL, float[] outR, float[] sendBus, int frames,
	                int outRate, float aLow, float aHigh, float kSmooth) {
		if (finished) return;

		final double rate = (double) sampleRate / outRate * Math.max(0.05f, pitch);
		final int count = Math.min(tapCount, MAX_TAPS);
		final float vol = volume;

		for (int i = 0; i < frames; i++) {
			send += (targetSend - send) * kSmooth;
			float dry = sample(cursor);
			if (sendBus != null) sendBus[i] += dry * send;

			for (int t = 0; t < count; t++) {
				Tap tap = taps[t];
				if (!tap.active) continue;
				if (!tap.primed) tap.prime();

				float dl = tap.targetDelayLeft * sampleRate;
				float dr = tap.targetDelayRight * sampleRate;
				tap.delayLeft += (dl - tap.delayLeft) * kSmooth;
				tap.delayRight += (dr - tap.delayRight) * kSmooth;

				float gl0 = tap.gainLeft[0] += (tap.targetGainLeft[0] * vol - tap.gainLeft[0]) * kSmooth;
				float gl1 = tap.gainLeft[1] += (tap.targetGainLeft[1] * vol - tap.gainLeft[1]) * kSmooth;
				float gl2 = tap.gainLeft[2] += (tap.targetGainLeft[2] * vol - tap.gainLeft[2]) * kSmooth;
				float gr0 = tap.gainRight[0] += (tap.targetGainRight[0] * vol - tap.gainRight[0]) * kSmooth;
				float gr1 = tap.gainRight[1] += (tap.targetGainRight[1] * vol - tap.gainRight[1]) * kSmooth;
				float gr2 = tap.gainRight[2] += (tap.targetGainRight[2] * vol - tap.gainRight[2]) * kSmooth;

				if (gl0 + gl1 + gl2 + gr0 + gr1 + gr2 < 1e-6f) continue;

				float sl = sample(cursor - tap.delayLeft);
				tap.loL += aLow * (sl - tap.loL);
				float restL = sl - tap.loL;
				tap.miL += aHigh * (restL - tap.miL);
				outL[i] += gl0 * tap.loL + gl1 * tap.miL + gl2 * (restL - tap.miL);

				float sr = sample(cursor - tap.delayRight);
				tap.loR += aLow * (sr - tap.loR);
				float restR = sr - tap.loR;
				tap.miR += aHigh * (restR - tap.miR);
				outR[i] += gr0 * tap.loR + gr1 * tap.miR + gr2 * (restR - tap.miR);
			}

			cursor += rate;
			if (cursor >= pcm.length) {
				if (loop) cursor -= pcm.length;
				else if (cursor > pcm.length + sampleRate * 0.6) { finished = true; return; }
			}
		}
		if (stopping) finished = true;
	}

	/** Чтение с дробной позицией; до прихода звука в этой точке тишина. */
	private float sample(double index) {
		if (index < 0) return 0f;
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
