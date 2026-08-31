package dev.d3sound.mc.audio;

/**
 * Воздух: скорость звука и затухание с расстоянием.
 *
 * Скорость — из уравнения состояния с поправкой на водяной пар.
 * Затухание — по ISO 9613-1 (релаксация кислорода и азота): именно поэтому
 * далёкие звуки теряют верх, а не просто становятся тише.
 *
 * Значения сверены с таблицами стандарта: при 20 °C и 50 % влажности модель
 * даёт 4.7 / 29.7 / 105 дБ/км на 1 / 4 / 8 кГц.
 */
public final class Air {
	private static final double T0 = 273.15;
	private static final double P_REF = 101.325;
	private static final double R = 8.314462618;
	private static final double M_DRY = 0.0289647;
	private static final double M_H2O = 0.018016;

	public final float speedOfSound;
	private final float[] dbPerMeter = new float[Materials.BAND_COUNT];

	public Air(float temperatureC, float humidityPercent, float pressureKPa) {
		double xw = vaporFraction(temperatureC, humidityPercent, pressureKPa);
		double m = (1 - xw) * M_DRY + xw * M_H2O;
		double gamma = (1 - xw) * 1.4 + xw * 1.33;
		this.speedOfSound = (float) Math.sqrt(gamma * R * (temperatureC + T0) / m);
		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			dbPerMeter[b] = (float) absorption(Materials.BANDS[b], temperatureC, humidityPercent, pressureKPa);
		}
	}

	/** Прямое задание среды: скорость и поглощение по полосам. */
	private Air(float speedOfSound, float[] dbPerMeterByBand) {
		this.speedOfSound = speedOfSound;
		System.arraycopy(dbPerMeterByBand, 0, dbPerMeter, 0, Materials.BAND_COUNT);
	}

	/**
	 * Вода. Звук идёт вчетверо быстрее воздуха и почти не гаснет: под водой
	 * далёкое слышно лучше, чем на суше, — глухо звучит не сама вода, а
	 * переход через её поверхность.
	 */
	public static final Air WATER = new Air(1484f,
		new float[]{1e-5f, 3e-5f, 1e-4f, 4e-4f, 1.4e-3f, 5e-3f, 1.8e-2f});

	/** Лава: вязкая и горячая, верх съедается почти сразу. */
	public static final Air LAVA = new Air(1100f,
		new float[]{0.02f, 0.05f, 0.12f, 0.3f, 0.8f, 2f, 5f});

	/**
	 * Потери на границе воздух — вода, дБ по полосам.
	 *
	 * Сопротивления сред различаются в тысячи раз, поэтому почти вся энергия
	 * отражается обратно; сквозь поверхность проходит в основном низ. Отсюда и
	 * знакомое «из-под воды слышно только гул».
	 */
	public static final float[] SURFACE_LOSS_DB = {10f, 13f, 17f, 21f, 26f, 32f, 39f};

	/** Нижний мир: сухо и жарко, звук идёт заметно быстрее и дальше несёт верх. */
	public static Air nether() { return new Air(75f, 5f, 101.325f); }

	/** Энд: холодная разрежённая пустота — звук медленнее и почти не гаснет. */
	public static Air end() { return new Air(-10f, 0f, 60f); }

	/** Погода Minecraft: в дождь воздух влажный, в снег — холодный. */
	public static Air forWeather(boolean raining, boolean snowing, float biomeTemperature) {
		float t = 8 + biomeTemperature * 22f;             // биомная «температура» 0…1 → −? …30 °C
		float h = raining ? 90f : (snowing ? 75f : 45f);
		if (snowing) t = Math.min(t, -2f);
		return new Air(t, h, 101.325f);
	}

	public float dbPerMeter(int band) { return dbPerMeter[band]; }

	/** Коэффициент по амплитуде на дистанции. */
	public float gain(int band, float distance) {
		return (float) Math.pow(10.0, -(dbPerMeter[band] * distance) / 20.0);
	}

	private static double vaporFraction(double tempC, double humidity, double pressure) {
		double t = tempC + T0;
		double psat = P_REF * Math.pow(10, -6.8346 * Math.pow(273.16 / t, 1.261) + 4.6151);
		double x = (Math.max(0, Math.min(100, humidity)) / 100) * psat / Math.max(1, pressure);
		return Math.max(0, Math.min(0.9, x));
	}

	private static double absorption(double f, double tempC, double humidity, double pressure) {
		double t = tempC + T0;
		double pa = pressure / P_REF;
		double tr = t / 293.15;
		double h = 100 * vaporFraction(tempC, humidity, pressure);

		double frO = pa * (24 + 4.04e4 * h * (0.02 + h) / (0.391 + h));
		double frN = pa * Math.pow(tr, -0.5) * (9 + 280 * h * Math.exp(-4.170 * (Math.pow(tr, -1.0 / 3) - 1)));

		double f2 = f * f;
		double classic = 1.84e-11 / pa * Math.sqrt(tr);
		double oxygen = 0.01275 * Math.exp(-2239.1 / t) / (frO + f2 / frO);
		double nitrogen = 0.1068 * Math.exp(-3352.0 / t) / (frN + f2 / frN);
		return 8.686 * f2 * (classic + Math.pow(tr, -2.5) * (oxygen + nitrogen));
	}
}
