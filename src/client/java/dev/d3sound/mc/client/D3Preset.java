package dev.d3sound.mc.client;

/**
 * Готовые наборы настроек.
 *
 * Ничего нового пресет не умеет — он просто расставляет те же ползунки и
 * переключатели разом, чтобы не подбирать десяток значений вручную. Громкость,
 * счётчик на экране и сам выключатель движка пресеты не трогают: это личное и
 * от качества расчёта не зависит.
 */
public enum D3Preset {
	/**
	 * Вся физика и много процессора: столько лучей и отражений, сколько машина
	 * даст. Ближе всего к тому, как звук ведёт себя на самом деле.
	 */
	REALISTIC_HIGH("realistic_high", 0, 60, 90, true, true, true, 100, 0, 100),

	/**
	 * Та же физика, но в вдвое меньший процессор: все механизмы на месте,
	 * просто лучей меньше и картина грубее.
	 */
	REALISTIC_LOW("realistic_low", 0, 18, 60, true, true, true, 100, 0, 100),

	/** Значения по умолчанию: всё включено, процессора берётся умеренно. */
	BALANCED("balanced", 0, 40, 70, true, true, true, 100, 0, 100),

	/**
	 * Самое дешёвое, что ещё не ванильный звук: остаются направление, расстояние
	 * и глухота за преградой, отражения и звук по блокам выключены.
	 */
	PERFORMANCE("performance", 20, 8, 50, true, false, false, 100, 0, 100),

	/**
	 * Красиво, а не точно.
	 *
	 * Хвост богаче настоящего, стены передают звук чище и глуше, доплер чуть
	 * подчёркнут. Физически это уже неправда, зато звучит объёмнее большинства
	 * игровых движков.
	 */
	CINEMATIC("cinematic", 0, 50, 85, true, true, true, 60, 130, 115);

	/** Ключ перевода: {@code d3sound.preset.<key>} и {@code …<key>.tip}. */
	public final String key;
	public final int quality;
	public final int cpuShare;
	public final int cpuHeadroom;
	public final boolean diffraction;
	public final boolean reflections;
	public final boolean structure;
	public final int structureLevel;
	public final int reverb;
	public final int doppler;

	D3Preset(String key, int quality, int cpuShare, int cpuHeadroom,
	         boolean diffraction, boolean reflections, boolean structure,
	         int structureLevel, int reverb, int doppler) {
		this.key = key;
		this.quality = quality;
		this.cpuShare = cpuShare;
		this.cpuHeadroom = cpuHeadroom;
		this.diffraction = diffraction;
		this.reflections = reflections;
		this.structure = structure;
		this.structureLevel = structureLevel;
		this.reverb = reverb;
		this.doppler = doppler;
	}

	public String caption() { return "d3sound.preset." + key; }

	public String tooltip() { return "d3sound.preset." + key + ".tip"; }

	/** Расставить свои значения. Громкость, счётчик и выключатель не трогаются. */
	public void applyTo(D3Config config) {
		config.quality = quality;
		config.cpuShare = cpuShare;
		config.cpuHeadroom = cpuHeadroom;
		config.diffraction = diffraction;
		config.reflections = reflections;
		config.structure = structure;
		config.structureLevel = structureLevel;
		config.reverb = reverb;
		config.doppler = doppler;
	}

	/** Совпадают ли текущие настройки с этим набором. */
	public boolean matches(D3Config config) {
		return config.quality == quality
			&& config.cpuShare == cpuShare
			&& config.cpuHeadroom == cpuHeadroom
			&& config.diffraction == diffraction
			&& config.reflections == reflections
			&& config.structure == structure
			&& config.structureLevel == structureLevel
			&& config.reverb == reverb
			&& config.doppler == doppler;
	}

	/** Какой набор сейчас выставлен; {@code null}, если настройки правились руками. */
	public static D3Preset current(D3Config config) {
		for (D3Preset preset : values()) {
			if (preset.matches(config)) return preset;
		}
		return null;
	}
}
