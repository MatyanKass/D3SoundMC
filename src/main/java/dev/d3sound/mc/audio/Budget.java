package dev.d3sound.mc.audio;

import java.lang.management.ManagementFactory;

/**
 * Автоподстройка нагрузки.
 *
 * Идея простая: смотреть, насколько занят процессор, и брать столько, сколько
 * он готов отдать, оставляя запас. Есть свободные ядра — поднимаем число лучей,
 * глубину отражений и частоту пересчёта; система загружена или прогон затянулся —
 * быстро отступаем.
 *
 * Вверх качество ползёт медленно, вниз падает резко: лучше на секунду потерять
 * в точности, чем уронить кадры.
 */
public final class Budget {
	private final com.sun.management.OperatingSystemMXBean os;

	/** До какой загрузки системы поднимаем качество. */
	public volatile float targetLoad = 0.70f;
	/** Выше этой — резко сбрасываем. */
	public volatile float panicLoad = 0.88f;
	/**
	 * Жёсткий потолок времени одного прогона, мс.
	 *
	 * Это уже не про нагрузку, а про запаздывание: решение старше этого
	 * времени описывает мир, которого вокруг игрока может не быть.
	 */
	public volatile float maxSolveMs = 45f;
	/**
	 * Сколько процессора движку позволено съесть самому, долей от всего
	 * процессора. Считается честно: время прогона делённое на период между
	 * прогонами даёт занятую долю ядра, а деление на число ядер переводит её
	 * в долю всей машины.
	 */
	public volatile float ownShareLimit = 0.40f;

	/** Ручное качество 0…1; отрицательное значение — режим «Авто». */
	public volatile float manualQuality = -1f;
	/** Считать ли обходные пути (дифракцию). */
	public volatile boolean diffraction = true;
	/** Считать ли отражения. */
	public volatile boolean reflections = true;
	/** Считать ли звук, идущий по самим блокам. */
	public volatile boolean structure = true;
	/** Множитель уровня структурного звука. */
	public volatile float structureGain = 1f;
	/** Считать ли звук, прошедший преграду насквозь. */
	public volatile boolean transmission = true;
	/** Множитель уровня прошедшего сквозь преграду звука. */
	public volatile float transmissionGain = 1f;
	/**
	 * Дальность расчёта, блоков; 0 — подбирать по качеству.
	 *
	 * Радиус снимка мира — самая дорогая настройка после лучей: объём растёт
	 * кубом, и вдвое больший радиус это восьмикратный снимок.
	 */
	public volatile int manualRadius = 0;
	/** Период пересчёта, мс; 0 — подбирать по качеству. */
	public volatile int manualIntervalMs = 0;
	/** Сколько источников считать честно; 0 — подбирать по качеству. */
	public volatile int manualSources = 0;
	/**
	 * Множитель потерь на кромке преграды.
	 *
	 * 1 — как в жизни. Больше — из-за угла глуше, меньше — звук огибает
	 * преграды охотнее, чем следовало бы.
	 */
	public volatile float diffractionGain = 1f;

	/** Текущее качество 0…1. */
	private float quality = 0.35f;
	private float loadAvg = 0.4f;
	private float solveAvg = 4f;
	private float shareAvg = 0.05f;
	private final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

	public Budget() {
		com.sun.management.OperatingSystemMXBean bean = null;
		try {
			bean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		} catch (Throwable ignored) {
			// на экзотических JVM просто останемся на средних настройках
		}
		this.os = bean;
	}

	/** Учесть результат очередного прогона. */
	public void update(float solveMs) {
		solveAvg += (solveMs - solveAvg) * 0.3f;

		// сколько процессора съели мы сами: занятая доля ядра, помноженная на
		// число рабочих потоков и делённая на все ядра машины
		float duty = solveAvg / Math.max(1f, intervalMs());
		shareAvg += (Math.min(1f, duty) * threads() / cores - shareAvg) * 0.3f;

		if (manualQuality >= 0) {
			quality = Math.max(0.05f, Math.min(1f, manualQuality));
			return;
		}

		float load = 0.5f;
		if (os != null) {
			double sys = os.getCpuLoad();
			double self = os.getProcessCpuLoad();
			double value = Math.max(sys, self);
			if (value >= 0 && value <= 1) load = (float) value;
		}
		loadAvg += (load - loadAvg) * 0.25f;

		float allowed = allowedSolveMs();
		boolean tooSlow = solveAvg > allowed;
		if (shareAvg > ownShareLimit) {
			quality -= 0.10f;                       // вышли за свой потолок
		} else if (loadAvg > panicLoad || tooSlow) {
			quality -= 0.12f;                       // машине плохо — отступаем резко
		} else if (shareAvg < ownShareLimit * 0.6f
			&& loadAvg < targetLoad - 0.12f && solveAvg < allowed * 0.6f) {
			quality += 0.02f;                       // поднимаемся осторожно
		} else if (loadAvg > targetLoad) {
			quality -= 0.03f;
		}
		quality = Math.max(0.05f, Math.min(1f, quality));
	}

	public float quality() { return quality; }
	public float load() { return loadAvg; }
	public float solveMs() { return solveAvg; }

	/** Доля всего процессора, которую сейчас занимает движок. */
	public float ownShare() { return shareAvg; }

	public int cores() { return cores; }

	/**
	 * Сколько может длиться один прогон, чтобы уложиться в отведённую долю.
	 *
	 * Прогоны идут с периодом {@link #intervalMs()}, значит занятая доля ядра
	 * это время прогона к периоду; умножив разрешённую долю машины на число
	 * ядер, получаем, сколько миллисекунд нам позволено.
	 */
	public float allowedSolveMs() {
		float byShare = ownShareLimit * cores * intervalMs() / threads();
		return Math.max(4f, Math.min(maxSolveMs, byShare));
	}

	/**
	 * Во сколько потоков считать лучи.
	 *
	 * Один поток может занять не больше одного ядра, то есть 1/N машины: на
	 * двенадцатиядерном это 8 %, и никакой потолок выше просто не достижим.
	 * Поэтому число потоков берём из отведённой доли — столько ядер нам и
	 * разрешено занять, — оставляя как минимум одно ядро самой игре.
	 */
	public int threads() {
		int wanted = Math.round(ownShareLimit * cores);
		return Math.max(1, Math.min(Math.max(1, cores - 1), wanted));
	}

	/* --- во что превращается качество --- */

	public int rays() { return Math.round(lerp(96, 3072, curve(quality))); }

	public int bounces() { return Math.round(lerp(4, 16, quality)); }

	/** Сколько отражений на источник доходит до микшера. */
	public int taps() { return Math.round(lerp(2, Solution.MAX_TAPS, quality)); }

	/** Период пересчёта, мс. */
	public int intervalMs() {
		if (manualIntervalMs > 0) return Math.max(20, Math.min(500, manualIntervalMs));
		return Math.round(lerp(260, 70, quality));
	}

	/**
	 * Сколько источников считать честно.
	 *
	 * Остальные идут упрощённо, без геометрии. Каждый источник — это отдельный
	 * сбор энергии на каждом отскоке каждого луча, так что цена растёт линейно.
	 */
	public int sources() {
		if (manualSources > 0) return Math.max(1, Math.min(Tracer.MAX_SOURCES, manualSources));
		return Math.round(lerp(6, Tracer.MAX_SOURCES, quality));
	}

	/** Радиус снимка мира, блоков. */
	public int radius() {
		if (manualRadius > 0) return Math.max(8, Math.min(64, manualRadius));
		return Math.round(lerp(14, 32, quality));
	}

	private static float lerp(float a, float b, float t) { return a + (b - a) * Math.max(0, Math.min(1, t)); }

	/** Лучи дороже всего — растут не линейно, а мягче. */
	private static float curve(float q) { return q * q * 0.7f + q * 0.3f; }

	public String describe() {
		return String.format("качество %d%%%s · движок ест %.1f%% ЦП из %.0f%% · система %d%% · прогон %.1f мс · лучей %d",
			Math.round(quality * 100), manualQuality >= 0 ? "" : " (авто)",
			shareAvg * 100, ownShareLimit * 100, Math.round(loadAvg * 100), solveAvg, rays())
			+ String.format(" · потоков %d", threads());
	}
}
