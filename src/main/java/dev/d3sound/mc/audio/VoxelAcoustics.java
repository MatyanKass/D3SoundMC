package dev.d3sound.mc.audio;

/**
 * Акустика воксельного мира.
 *
 * Мир Minecraft — готовая акустическая сетка: каждый блок это куб с известным
 * материалом. Поэтому вместо дерева ограничивающих объёмов работает шаговый
 * обход по сетке (алгоритм Амануатидеса–Ву) — он на порядок дешевле и точно
 * ложится на структуру мира.
 *
 * Отсюда получаются две вещи:
 *   • перекрытие прямого пути — какие блоки стоят между источником и слушателем
 *     и сколько энергии проходит сквозь них в каждой полосе;
 *   • свойства помещения вокруг слушателя — зондирование лучами даёт средний
 *     свободный пробег, среднее поглощение и открытость, а из них время
 *     реверберации по Эйрингу.
 */
public final class VoxelAcoustics {

	/** Доступ к блокам мира. Возвращает null, если блок не преграда (воздух). */
	public interface BlockSampler {
		Materials at(int x, int y, int z);
	}

	private VoxelAcoustics() {}

	/* ------------------------------------------------------------------ */
	/*  перекрытие                                                         */
	/* ------------------------------------------------------------------ */

	/**
	 * Прохождение прямого пути сквозь блоки: заполняет out[] коэффициентами
	 * по амплитуде для каждой полосы и возвращает число перекрывших блоков.
	 */
	public static int occlusion(BlockSampler world,
	                            double sx, double sy, double sz,
	                            double lx, double ly, double lz,
	                            float[] out) {
		for (int b = 0; b < Materials.BAND_COUNT; b++) out[b] = 1f;

		double dx = lx - sx, dy = ly - sy, dz = lz - sz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1e-6) return 0;
		dx /= dist; dy /= dist; dz /= dist;

		int x = (int) Math.floor(sx), y = (int) Math.floor(sy), z = (int) Math.floor(sz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = Math.abs(1 / (dx == 0 ? 1e-12 : dx));
		double tDeltaY = Math.abs(1 / (dy == 0 ? 1e-12 : dy));
		double tDeltaZ = Math.abs(1 / (dz == 0 ? 1e-12 : dz));
		double tMaxX = boundary(sx, dx, stepX) * tDeltaX;
		double tMaxY = boundary(sy, dy, stepY) * tDeltaY;
		double tMaxZ = boundary(sz, dz, stepZ) * tDeltaZ;

		int blocked = 0;
		double travelled = 0;
		int guard = 0;
		while (travelled < dist && guard++ < 512) {
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) { x += stepX; travelled = tMaxX; tMaxX += tDeltaX; }
				else { z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ; }
			} else {
				if (tMaxY < tMaxZ) { y += stepY; travelled = tMaxY; tMaxY += tDeltaY; }
				else { z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ; }
			}
			if (travelled >= dist) break;

			Materials m = world.at(x, y, z);
			if (m == null) continue;
			blocked++;
			for (int b = 0; b < Materials.BAND_COUNT; b++) out[b] *= m.transmissionGain(b);
			if (blocked > 12) break;      // дальше уже тишина
		}
		return blocked;
	}

	private static double boundary(double origin, double dir, int step) {
		double cell = Math.floor(origin);
		return step > 0 ? (cell + 1 - origin) : (origin - cell);
	}

	/* ------------------------------------------------------------------ */
	/*  зондирование помещения                                             */
	/* ------------------------------------------------------------------ */

	/** Результат зондирования пространства вокруг точки. */
	public static final class Probe {
		public final float[] rt60 = new float[Materials.BAND_COUNT];
		public float meanFreePath;      // м
		public float openness;          // 0 — замкнуто, 1 — открытое небо
		public float volume;            // грубая оценка объёма, м³
		public float meanAbsorption;    // на 500 Гц, для отладки
	}

	private static final int PROBE_RAYS = 64;
	private static final float MAX_PROBE = 48f;

	/**
	 * Зондирование лучами: средний свободный пробег, среднее поглощение по
	 * полосам и открытость. Время реверберации — по Эйрингу на измеренных
	 * величинах: RT60 = −60·(λ/c) / (10·lg(1−ᾱ)).
	 */
	public static Probe probe(BlockSampler world, double px, double py, double pz, float speedOfSound) {
		Probe result = new Probe();
		float[] absorbSum = new float[Materials.BAND_COUNT];
		float distanceSum = 0;
		int hits = 0;
		int escapes = 0;

		for (int i = 0; i < PROBE_RAYS; i++) {
			// равномерные направления по сфере (решётка Фибоначчи)
			double k = i + 0.5;
			double phi = Math.acos(1 - 2 * k / PROBE_RAYS);
			double theta = Math.PI * (1 + Math.sqrt(5)) * k;
			double st = Math.sin(phi);
			double dx = st * Math.cos(theta), dy = Math.cos(phi), dz = st * Math.sin(theta);

			Hit hit = cast(world, px, py, pz, dx, dy, dz, MAX_PROBE);
			if (hit.material == null) {
				escapes++;
				distanceSum += MAX_PROBE;
				continue;
			}
			hits++;
			distanceSum += hit.distance;
			for (int b = 0; b < Materials.BAND_COUNT; b++) absorbSum[b] += hit.material.absorption[b];
		}

		float meanDistance = distanceSum / PROBE_RAYS;
		result.meanFreePath = Math.max(0.5f, meanDistance * 2f);   // хорда вдвое длиннее луча из точки
		result.openness = escapes / (float) PROBE_RAYS;
		// объём как у сферы эквивалентного радиуса — грубо, но для оценки хватает
		result.volume = (float) (4.0 / 3.0 * Math.PI * Math.pow(meanDistance, 3));

		float timeBetween = result.meanFreePath / speedOfSound;
		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			float alpha = hits > 0 ? absorbSum[b] / hits : 0.9f;
			// уходящие в небо лучи считаем полным поглощением
			alpha = alpha * (1 - result.openness) + result.openness;
			alpha = Math.min(0.98f, Math.max(0.01f, alpha));
			float dbPerReflection = (float) (-10 * Math.log10(1 - alpha));
			float dbPerSecond = dbPerReflection / Math.max(1e-4f, timeBetween);
			result.rt60[b] = Math.min(12f, 60f / Math.max(0.5f, dbPerSecond));
			if (b == 2) result.meanAbsorption = alpha;
		}
		return result;
	}

	/** Результат броска одного луча. */
	public static final class Hit {
		public Materials material;
		public float distance;
		public int nx, ny, nz;
	}

	private static final ThreadLocal<Hit> HIT = ThreadLocal.withInitial(Hit::new);

	/** Бросок луча по сетке до первого непрозрачного блока. */
	public static Hit cast(BlockSampler world, double ox, double oy, double oz,
	                       double dx, double dy, double dz, float maxDistance) {
		Hit hit = HIT.get();
		hit.material = null;
		hit.distance = maxDistance;
		hit.nx = hit.ny = hit.nz = 0;

		int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = Math.abs(1 / (dx == 0 ? 1e-12 : dx));
		double tDeltaY = Math.abs(1 / (dy == 0 ? 1e-12 : dy));
		double tDeltaZ = Math.abs(1 / (dz == 0 ? 1e-12 : dz));
		double tMaxX = boundary(ox, dx, stepX) * tDeltaX;
		double tMaxY = boundary(oy, dy, stepY) * tDeltaY;
		double tMaxZ = boundary(oz, dz, stepZ) * tDeltaZ;

		double travelled = 0;
		int guard = 0;
		while (travelled < maxDistance && guard++ < 512) {
			int fx = 0, fy = 0, fz = 0;
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) { x += stepX; travelled = tMaxX; tMaxX += tDeltaX; fx = -stepX; }
				else { z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ; fz = -stepZ; }
			} else {
				if (tMaxY < tMaxZ) { y += stepY; travelled = tMaxY; tMaxY += tDeltaY; fy = -stepY; }
				else { z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ; fz = -stepZ; }
			}
			Materials m = world.at(x, y, z);
			if (m != null) {
				hit.material = m;
				hit.distance = (float) travelled;
				hit.nx = fx; hit.ny = fy; hit.nz = fz;
				return hit;
			}
		}
		return hit;
	}
}
