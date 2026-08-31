package dev.d3sound.mc.audio;

/**
 * Акустические свойства материалов по октавным полосам 125…8000 Гц.
 *
 * Для каждого материала заданы:
 *   absorption   — доля поглощаемой энергии α;
 *   scattering   — доля энергии, уходящей не зеркально (шероховатость);
 *   transmission — звукоизоляция R в дБ: сколько теряет звук, прошедший сквозь.
 *
 * Значения — типовые справочные для соответствующих реальных поверхностей.
 * Блоки Minecraft раскладываются по этим материалам в {@link #ofSoundType}.
 */
public enum Materials {
	STONE   ("камень",  new float[]{0.01f, 0.01f, 0.02f, 0.02f, 0.02f, 0.03f, 0.03f},
	                    new float[]{0.10f, 0.12f, 0.14f, 0.16f, 0.18f, 0.20f, 0.22f},
	                    new float[]{40, 45, 50, 55, 60, 64, 64}),
	BRICK   ("кирпич",  new float[]{0.03f, 0.03f, 0.03f, 0.04f, 0.05f, 0.07f, 0.08f},
	                    new float[]{0.14f, 0.16f, 0.20f, 0.24f, 0.28f, 0.30f, 0.32f},
	                    new float[]{38, 42, 45, 50, 55, 58, 58}),
	WOOD    ("дерево",  new float[]{0.15f, 0.11f, 0.10f, 0.07f, 0.06f, 0.07f, 0.07f},
	                    new float[]{0.08f, 0.10f, 0.12f, 0.15f, 0.18f, 0.20f, 0.22f},
	                    new float[]{20, 24, 27, 30, 33, 35, 35}),
	WOOL    ("шерсть",  new float[]{0.08f, 0.24f, 0.57f, 0.69f, 0.71f, 0.73f, 0.73f},
	                    new float[]{0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f},
	                    new float[]{4, 6, 8, 10, 12, 14, 14}),
	GLASS   ("стекло",  new float[]{0.35f, 0.25f, 0.18f, 0.12f, 0.07f, 0.04f, 0.04f},
	                    new float[]{0.02f, 0.03f, 0.03f, 0.04f, 0.05f, 0.05f, 0.05f},
	                    new float[]{20, 22, 26, 29, 31, 25, 28}),
	METAL   ("металл",  new float[]{0.05f, 0.04f, 0.04f, 0.04f, 0.05f, 0.05f, 0.05f},
	                    new float[]{0.04f, 0.05f, 0.06f, 0.07f, 0.08f, 0.10f, 0.10f},
	                    new float[]{18, 22, 25, 28, 30, 26, 28}),
	SAND    ("песок",   new float[]{0.15f, 0.25f, 0.40f, 0.55f, 0.60f, 0.60f, 0.55f},
	                    new float[]{0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.50f},
	                    new float[]{30, 35, 40, 45, 50, 52, 52}),
	SNOW    ("снег",    new float[]{0.45f, 0.75f, 0.90f, 0.95f, 0.95f, 0.95f, 0.90f},
	                    new float[]{0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f, 0.60f},
	                    new float[]{8, 11, 14, 16, 18, 20, 20}),
	DIRT    ("земля",   new float[]{0.10f, 0.18f, 0.28f, 0.38f, 0.45f, 0.48f, 0.48f},
	                    new float[]{0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.45f},
	                    new float[]{32, 37, 42, 47, 52, 55, 55}),
	FOLIAGE ("листва",  new float[]{0.20f, 0.30f, 0.40f, 0.50f, 0.55f, 0.60f, 0.60f},
	                    new float[]{0.60f, 0.65f, 0.70f, 0.75f, 0.80f, 0.85f, 0.85f},
	                    new float[]{2, 3, 5, 6, 8, 9, 9}),
	WATER   ("вода",    new float[]{0.02f, 0.02f, 0.03f, 0.03f, 0.04f, 0.05f, 0.05f},
	                    new float[]{0.05f, 0.06f, 0.08f, 0.10f, 0.12f, 0.14f, 0.15f},
	                    new float[]{25, 30, 35, 40, 45, 48, 48}),
	SOFT    ("мягкое",  new float[]{0.25f, 0.45f, 0.60f, 0.70f, 0.75f, 0.78f, 0.78f},
	                    new float[]{0.35f, 0.40f, 0.45f, 0.50f, 0.55f, 0.60f, 0.60f},
	                    new float[]{6, 9, 12, 14, 16, 18, 18});

	/** Центральные частоты полос, Гц. */
	public static final float[] BANDS = {125, 250, 500, 1000, 2000, 4000, 8000};
	public static final int BAND_COUNT = BANDS.length;

	public final String label;
	public final float[] absorption;
	public final float[] scattering;
	public final float[] transmission;

	Materials(String label, float[] absorption, float[] scattering, float[] transmission) {
		this.label = label;
		this.absorption = absorption;
		this.scattering = scattering;
		this.transmission = transmission;
	}

	/** Коэффициент прохождения по амплитуде для полосы. */
	public float transmissionGain(int band) {
		return (float) Math.pow(10.0, -transmission[band] / 20.0);
	}

	/**
	 * Материал по типу звука блока. Сравнение по ссылке на константы
	 * {@code SoundType} делается в игровой прослойке, сюда приходит уже имя.
	 */
	public static Materials byKey(String key) {
		return switch (key) {
			case "wood", "bamboo", "ladder", "scaffolding", "stem", "bamboo_wood" -> WOOD;
			case "wool", "candle", "cloth" -> WOOL;
			case "glass", "amethyst" -> GLASS;
			case "metal", "chain", "anvil", "netherite", "lodestone", "copper" -> METAL;
			case "sand", "gravel", "soul_sand", "powder_snow_bucket" -> SAND;
			case "snow", "powder_snow" -> SNOW;
			case "grass", "dirt", "nylium", "roots", "moss", "mud" -> DIRT;
			case "vine", "leaves", "crop", "sweet_berry_bush", "nether_sprouts", "hanging_roots" -> FOLIAGE;
			case "water", "wet_grass", "lily_pad" -> WATER;
			case "slime_block", "honey_block", "sponge", "bed" -> SOFT;
			case "nether_bricks", "bricks", "deepslate_bricks", "tuff_bricks", "mud_bricks" -> BRICK;
			default -> STONE;
		};
	}
}
