package dev.d3sound.mc.audio;

/**
 * Бинауральная модель головы.
 *
 * Голова считается сферой: межушная задержка берётся по формуле Вудворта,
 * а тень головы — частотно зависимой: низ огибает голову почти без потерь,
 * верх экранируется на 17–19 дБ. Плюс подсказка «спереди или сзади» — приход
 * сзади глуше на верхних полосах.
 *
 * Значения сверены с измеренными HRTF: на 90° сбоку задержка 0.65 мс,
 * разница уровней 2 / 9 / 18 дБ на низе / середине / верхе.
 */
public final class Binaural {

	/** Ослабление дальнего уха по полосам, дБ. */
	private static final float[] SHADOW_DB = {1.5f, 3f, 6f, 9f, 13f, 17f, 19f};
	/** Ослабление источника строго сзади, дБ. */
	private static final float[] REAR_DB = {0f, 0.5f, 1.5f, 3f, 5f, 7f, 8f};

	/** Радиус головы, м. */
	public static final float HEAD_RADIUS = 0.0875f;

	/** Результат: задержки на уши и усиления в трёх полосах. */
	public static final class Ears {
		public float delayLeft;      // секунды (распространение + межушная)
		public float delayRight;
		public final float[] gainLeft = new float[3];   // низ / середина / верх
		public final float[] gainRight = new float[3];
	}

	private final float[] bandGain = new float[Materials.BAND_COUNT];

	/**
	 * Расчёт для одного пути.
	 *
	 * @param dir       направление на источник в системе слушателя
	 *                  (x — вправо, y — вверх, z — вперёд)
	 * @param distance  расстояние, м
	 * @param air       модель воздуха
	 * @param occlusion коэффициенты прохождения сквозь преграды по полосам (или null)
	 * @param volume    громкость источника
	 */
	public void compute(float[] dir, float distance, Air air, float[] occlusion,
	                    float volume, float minDistance, Ears out) {
		float d = Math.max(minDistance, distance);
		float spread = volume / d;

		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			float g = spread * air.gain(b, distance);
			if (occlusion != null) g *= occlusion[b];
			bandGain[b] = g;
		}

		float x = dir[0], y = dir[1], z = dir[2];
		float azimuth = (float) Math.atan2(x, z);
		float elevation = (float) Math.asin(Math.max(-1, Math.min(1, y)));
		float rear = Math.max(0, -z);

		// межушная задержка по Вудворту
		float theta = (float) Math.min(Math.PI / 2, Math.abs(azimuth));
		float itd = (HEAD_RADIUS / air.speedOfSound) * (theta + (float) Math.sin(theta)) * (float) Math.cos(elevation);
		if (azimuth < 0) itd = -itd;

		float base = distance / air.speedOfSound + HEAD_RADIUS / air.speedOfSound;
		out.delayLeft = base + itd / 2;
		out.delayRight = base - itd / 2;

		for (int i = 0; i < 3; i++) { out.gainLeft[i] = 0; out.gainRight[i] = 0; }

		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			float v = bandGain[b];
			if (rear > 0) v *= (float) Math.pow(10.0, -(REAR_DB[b] * rear) / 20.0);
			float vl = v * (float) Math.pow(10.0, -(SHADOW_DB[b] * (1 + x) / 2) / 20.0);
			float vr = v * (float) Math.pow(10.0, -(SHADOW_DB[b] * (1 - x) / 2) / 20.0);
			int g = b <= 1 ? 0 : (b <= 4 ? 1 : 2);
			out.gainLeft[g] += vl;
			out.gainRight[g] += vr;
		}
		out.gainLeft[0] /= 2; out.gainRight[0] /= 2;
		out.gainLeft[1] /= 3; out.gainRight[1] /= 3;
		out.gainLeft[2] /= 2; out.gainRight[2] /= 2;
	}

	/** Перевод мирового направления в систему слушателя (yaw, затем pitch). */
	public static void toListenerFrame(double dx, double dy, double dz,
	                                   float yawDeg, float pitchDeg, float[] out) {
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-9) { out[0] = 0; out[1] = 0; out[2] = 1; return; }
		dx /= len; dy /= len; dz /= len;

		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		// поворот в плоскости XZ: получаем «вправо» и «вперёд»
		double cy = Math.cos(yaw), sy = Math.sin(yaw);
		double right = dx * cy - dz * sy;
		double forward = dx * sy + dz * cy;
		double cp = Math.cos(pitch), sp = Math.sin(pitch);
		double up = dy * cp - forward * sp;
		double fwd = dy * sp + forward * cp;

		out[0] = (float) right;
		out[1] = (float) up;
		out[2] = (float) fwd;
	}
}
