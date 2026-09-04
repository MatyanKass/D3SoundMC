package dev.d3sound.mc.audio;

/**
 * Готовое акустическое решение для одного источника: набор путей, по которым
 * до слушателя доходит звук.
 *
 * Первый путь — прямой или обходной (дифракция), остальные — отражения,
 * найденные трассировкой: у каждого своя задержка, направление прихода и
 * спектр. Всё, что не попало в раннюю часть, уходит в хвост.
 */
public final class Solution {
	public static final int MAX_TAPS = 8;

	public int tapCount;
	public final float[] delay = new float[MAX_TAPS];
	public final float[][] bands = new float[MAX_TAPS][Materials.BAND_COUNT];
	public final float[][] dir = new float[MAX_TAPS][3];
	public float tailLevel;
	public boolean directBlocked;
	/** Насколько плотно перекрыт прямой путь, 0…1. */
	public float coverage;
	public float diffractionDb;
	/** Какой отвод пришёл по конструкции, а не по воздуху; −1, если такого нет. */
	public int structureTap = -1;

	/** Отвод «сквозь стену»: звук, прошедший преграду насквозь. -1, если такого нет. */
	public int transmissionTap = -1;

	/** Суммарная звукоизоляция преграды на пути, дБ (среднее по полосам). */
	public float transmissionDb;

	public void reset() {
		tapCount = 0;
		tailLevel = 0;
		directBlocked = false;
		coverage = 0;
		diffractionDb = 0;
		structureTap = -1;
		transmissionTap = -1;
		transmissionDb = 0;
	}

	public int addTap(float delaySeconds, float[] bandGains, float dx, float dy, float dz) {
		if (tapCount >= MAX_TAPS) return -1;
		int i = tapCount++;
		delay[i] = delaySeconds;
		System.arraycopy(bandGains, 0, bands[i], 0, Materials.BAND_COUNT);
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6f) { dir[i][0] = 0; dir[i][1] = 0; dir[i][2] = 1; }
		else { dir[i][0] = dx / len; dir[i][1] = dy / len; dir[i][2] = dz / len; }
		return i;
	}

	public void copyFrom(Solution other) {
		tapCount = other.tapCount;
		tailLevel = other.tailLevel;
		directBlocked = other.directBlocked;
		coverage = other.coverage;
		diffractionDb = other.diffractionDb;
		structureTap = other.structureTap;
		transmissionTap = other.transmissionTap;
		transmissionDb = other.transmissionDb;
		for (int i = 0; i < tapCount; i++) {
			delay[i] = other.delay[i];
			System.arraycopy(other.bands[i], 0, bands[i], 0, Materials.BAND_COUNT);
			System.arraycopy(other.dir[i], 0, dir[i], 0, 3);
		}
	}
}
