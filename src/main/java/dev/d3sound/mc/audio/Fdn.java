package dev.d3sound.mc.audio;

/**
 * Поздняя реверберация: сеть обратных связей из восьми линий задержки
 * с матрицей Адамара, входной диффузией на аллпассах и демпфированием ВЧ.
 *
 * Длины линий берутся из среднего свободного пробега помещения, затухание —
 * из времени реверберации, которое зондирование мира вернуло для этой точки.
 * Демпфирующий фильтр в петле сам съедает энергию, поэтому в расчёт затухания
 * введена поправка — иначе хвост выходит короче заданного.
 */
public final class Fdn {
	private static final int N = 8;
	private static final float[] RATIOS = {1.0f, 1.21f, 1.47f, 1.73f, 2.03f, 2.29f, 2.61f, 2.93f};
	private static final float NORM = 0.35355339f;   // 1/sqrt(8)

	private final int sampleRate;
	private final float[][] lines = new float[N][];
	private final int[] writePos = new int[N];
	private final float[] lengths = new float[N];
	private final float[] targetLengths = new float[N];
	private final float[] gains = new float[N];
	private final float[] lp = new float[N];
	private final float[] v = new float[N];

	private final float[] pre;
	private int preWrite;
	private float preLength;

	private final float[][] apBuf;
	private final int[] apPos;
	private final int[] apLen;
	private static final float AP_G = 0.58f;

	private float damping = 0.4f;
	private float rt60 = 1.0f;

	public Fdn(int sampleRate) {
		this.sampleRate = sampleRate;
		int max = (int) (sampleRate * 0.4f);
		for (int i = 0; i < N; i++) {
			lines[i] = new float[max];
			lengths[i] = targetLengths[i] = 700 + i * 137;
			gains[i] = 0.7f;
		}
		pre = new float[(int) (sampleRate * 0.2f) + 8];
		preLength = sampleRate * 0.01f;

		float scale = sampleRate / 44100f;
		int[] base = {142, 379, 107, 277};
		apBuf = new float[base.length][];
		apPos = new int[base.length];
		apLen = new int[base.length];
		for (int i = 0; i < base.length; i++) {
			apLen[i] = Math.max(4, Math.round(base[i] * scale));
			apBuf[i] = new float[apLen[i] + 4];
		}
	}

	/**
	 * Настроить хвост под помещение.
	 *
	 * @param rt60          время реверберации, с
	 * @param meanFreePath  средний свободный пробег, м
	 * @param speedOfSound  скорость звука, м/с
	 * @param dampingAmount 0 — без демпфирования, 1 — сильное
	 */
	public void configure(float rt60, float meanFreePath, float speedOfSound, float dampingAmount) {
		this.rt60 = Math.max(0.05f, rt60);
		this.damping = clamp(0.06f + 0.9f * (1 - dampingAmount), 0.02f, 0.98f);
		float baseSamples = Math.max(0.004f, meanFreePath / speedOfSound) * sampleRate;
		for (int i = 0; i < N; i++) {
			targetLengths[i] = clamp(baseSamples * RATIOS[i] + i * 7 + 11, 32, lines[i].length - 8);
		}
		preLength = clamp(meanFreePath / speedOfSound * 0.5f * sampleRate, 1, pre.length - 8);
		recomputeGains();
	}

	private void recomputeGains() {
		float comp = 1 + 0.5f * (1 - damping);
		for (int i = 0; i < N; i++) {
			gains[i] = (float) Math.pow(10.0, (-3.0 * targetLengths[i]) / (rt60 * comp * sampleRate));
		}
	}

	/** Обработать порцию: вход — моно посыл, выход домешивается в стерео. */
	public void process(float[] input, float[] outL, float[] outR, int frames, float wet) {
		for (int i = 0; i < N; i++) lengths[i] += (targetLengths[i] - lengths[i]) * 0.05f;

		for (int n = 0; n < frames; n++) {
			// предзадержка
			pre[preWrite] = input[n];
			float pr = preWrite - preLength;
			while (pr < 0) pr += pre.length;
			float sig = readLinear(pre, pr);
			preWrite = (preWrite + 1) % pre.length;

			// диффузия
			for (int i = 0; i < apBuf.length; i++) {
				int r = (apPos[i] + apBuf[i].length - apLen[i]) % apBuf[i].length;
				float delayed = apBuf[i][r];
				float in = sig - AP_G * delayed;
				apBuf[i][apPos[i]] = in;
				apPos[i] = (apPos[i] + 1) % apBuf[i].length;
				sig = delayed + AP_G * in;
			}

			// чтение линий
			for (int i = 0; i < N; i++) {
				float rp = writePos[i] - lengths[i];
				while (rp < 0) rp += lines[i].length;
				float y = readLinear(lines[i], rp);
				lp[i] += damping * (y - lp[i]);
				v[i] = lp[i];
			}

			outL[n] += (v[0] - v[1] + v[2] - v[3]) * 0.35f * wet;
			outR[n] += (v[4] - v[5] + v[6] - v[7]) * 0.35f * wet;

			hadamard(v);
			for (int i = 0; i < N; i++) {
				lines[i][writePos[i]] = sig + v[i] * gains[i];
				if (++writePos[i] >= lines[i].length) writePos[i] = 0;
			}
		}
	}

	private static void hadamard(float[] x) {
		for (int step = 1; step < N; step <<= 1) {
			for (int i = 0; i < N; i += step << 1) {
				for (int j = i; j < i + step; j++) {
					float a = x[j], b = x[j + step];
					x[j] = a + b;
					x[j + step] = a - b;
				}
			}
		}
		for (int i = 0; i < N; i++) x[i] *= NORM;
	}

	private static float readLinear(float[] buf, float pos) {
		int size = buf.length;
		if (pos >= size) pos -= size;
		if (pos < 0) pos += size;
		if (!(pos >= 0 && pos < size)) return 0f;
		int i0 = (int) pos;
		float frac = pos - i0;
		int i1 = i0 + 1 >= size ? 0 : i0 + 1;
		return buf[i0] + (buf[i1] - buf[i0]) * frac;
	}

	private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
