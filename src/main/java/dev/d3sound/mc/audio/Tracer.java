package dev.d3sound.mc.audio;

/**
 * Трассировка отражений по вокселям.
 *
 * Лучи выпускаются из слушателя — по взаимности это то же самое, что считать
 * из источника, но одним проходом обслуживаются все источники сразу, а
 * направление первого сегмента луча и есть направление, с которого звук придёт
 * в уши. Поэтому эхо слышно «вон от той стены», а не как обезличенный хвост.
 *
 * На каждом отражении:
 *   • энергия теряется по поглощению материала — отдельно в каждой полосе;
 *   • направление разыгрывается рулеткой: с вероятностью s (рассеяние
 *     материала) — диффузно по Ламберту, иначе зеркально;
 *   • если источник виден из точки отражения, часть энергии «капает» в него
 *     с задержкой по полной длине пути.
 *
 * Приходы раскладываются по временным бинам: ранние становятся отдельными
 * отводами задержки, поздние — уровнем хвоста.
 */
public final class Tracer {
	public static final int MAX_SOURCES = 24;
	public static final int BINS = 40;              // ранняя часть
	public static final float BIN_SECONDS = 0.005f; // 5 мс на бин → 200 мс окна

	private final int bands = Materials.BAND_COUNT;

	/** Энергия по источникам, бинам и полосам. */
	private final float[][] energy = new float[MAX_SOURCES][BINS * Materials.BAND_COUNT];
	/** Направление прихода, накопленное по энергии. */
	private final float[][] dirX = new float[MAX_SOURCES][BINS];
	private final float[][] dirY = new float[MAX_SOURCES][BINS];
	private final float[][] dirZ = new float[MAX_SOURCES][BINS];
	private final float[] lateEnergy = new float[MAX_SOURCES];

	private final float[] rayEnergy = new float[Materials.BAND_COUNT];
	private final float[] lossDb = new float[Materials.BAND_COUNT];
	public final float[] rt60 = new float[Materials.BAND_COUNT];

	private long seed = 0x9E3779B97F4A7C15L;
	public int raysUsed;
	public int bouncesUsed;
	public int depositsUsed;
	public float meanFreePath;
	private float travelTimeTotal;

	private float nextFloat() {
		seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
		return ((seed >>> 40) & 0xFFFFFF) / 16777216f;
	}

	public float energyAt(int source, int bin, int band) { return energy[source][bin * bands + band]; }

	public float lateEnergy(int source) { return lateEnergy[source]; }

	public void direction(int source, int bin, float[] out) {
		out[0] = dirX[source][bin];
		out[1] = dirY[source][bin];
		out[2] = dirZ[source][bin];
	}

	/**
	 * Прогон.
	 *
	 * @param sourceCount сколько источников в массивах sx/sy/sz (локальные координаты снимка)
	 * @param rays        сколько лучей выпустить
	 * @param maxBounces  предел отражений на луч
	 */
	public void trace(VoxelSnapshot world, double[] sx, double[] sy, double[] sz, int sourceCount,
	                  int rays, int maxBounces, float speedOfSound, float receiverRadius, long randomSeed) {
		reset(sourceCount);
		traceSlice(world, sx, sy, sz, sourceCount, 0, rays, rays, maxBounces, speedOfSound, receiverRadius, randomSeed);
		finish(speedOfSound);
	}

	/** Подготовить накопители под новый прогон. */
	public void reset(int sourceCount) {
		for (int s = 0; s < sourceCount; s++) {
			java.util.Arrays.fill(energy[s], 0f);
			java.util.Arrays.fill(dirX[s], 0f);
			java.util.Arrays.fill(dirY[s], 0f);
			java.util.Arrays.fill(dirZ[s], 0f);
			lateEnergy[s] = 0f;
		}
		java.util.Arrays.fill(lossDb, 0f);
		raysUsed = 0;
		bouncesUsed = 0;
		depositsUsed = 0;
		travelTimeTotal = 0;
	}

	/**
	 * Посчитать свою долю лучей.
	 *
	 * Направления берутся из общей спирали по номеру луча, поэтому куски не
	 * пересекаются и вместе дают то же покрытие сферы, что и один проход.
	 */
	public void traceSlice(VoxelSnapshot world, double[] sx, double[] sy, double[] sz, int sourceCount,
	                       int rayFrom, int rayTo, int rays, int maxBounces,
	                       float speedOfSound, float receiverRadius, long randomSeed) {
		seed = randomSeed | 1L;
		raysUsed += rayTo - rayFrom;

		double lx = world.toLocalX(world.listenerX);
		double ly = world.toLocalY(world.listenerY);
		double lz = world.toLocalZ(world.listenerZ);

		final float e0 = 1f / rays;
		final float receiverArea = receiverRadius * receiverRadius;
		final float window = BINS * BIN_SECONDS;
		float travelTime = 0;
		int totalBounces = 0;
		// у каждого куска свой поворот спирали, иначе они лягут одинаково
		seed ^= (long) rayFrom * 0x9E3779B97F4A7C15L;

		float rotA = nextFloat() * (float) Math.PI * 2;
		float rotB = nextFloat() * (float) Math.PI * 2;

		for (int r = rayFrom; r < rayTo; r++) {
			// равномерное направление по сфере
			double k = r + 0.5;
			double phi = Math.acos(1 - 2 * k / rays);
			double theta = Math.PI * (1 + Math.sqrt(5)) * k + rotA;
			double st = Math.sin(phi);
			float dx = (float) (st * Math.cos(theta));
			float dy = (float) Math.cos(phi);
			float dz = (float) (st * Math.sin(theta));
			float cb = (float) Math.cos(rotB), sb = (float) Math.sin(rotB);
			float tmp = dx * cb - dz * sb;
			dz = dx * sb + dz * cb;
			dx = tmp;

			// направление прихода в уши — это первый сегмент луча
			final float arriveX = dx, arriveY = dy, arriveZ = dz;

			double px = lx, py = ly, pz = lz;
			float travelled = 0;
			for (int b = 0; b < bands; b++) rayEnergy[b] = e0;

			for (int bounce = 0; bounce < maxBounces; bounce++) {
				Ray hit = march(world, px, py, pz, dx, dy, dz, 64f);
				if (hit.material == null) break;
				// march возвращает один и тот же объект, а проверка видимости
				// источника вызывает его снова — забираем попадание сразу
				final Materials material = hit.material;
				final float hitDistance = hit.distance;
				final float nx = hit.nx, ny = hit.ny, nz = hit.nz;
				totalBounces++;
				travelled += hitDistance;
				float time = travelled / speedOfSound;
				if (time > window * 4) break;

				px += dx * hitDistance;
				py += dy * hitDistance;
				pz += dz * hitDistance;

				Materials m = material;
				float total = 0;
				for (int b = 0; b < bands; b++) {
					float refl = Math.max(0.002f, 1 - m.absorption[b]);
					rayEnergy[b] *= refl;
					total += rayEnergy[b];
					lossDb[b] += (float) (-10 * Math.log10(refl));
				}
				travelTime += hitDistance / speedOfSound;
				if (total < 1e-7f) break;

				// сбор энергии в источники
				for (int s = 0; s < sourceCount; s++) {
					double vx = sx[s] - px, vy = sy[s] - py, vz = sz[s] - pz;
					double d2 = vx * vx + vy * vy + vz * vz;
					if (d2 < 0.01) continue;
					double d = Math.sqrt(d2);
					float arrival = (float) ((travelled + d) / speedOfSound);
					if (arrival > window * 4) continue;
					float rough = (float) (total * receiverArea / d2);
					if (rough < 1e-8f) continue;
					if (blocked(world, px, py, pz, sx[s], sy[s], sz[s])) continue;

					float inv = (float) (1 / d);
					float cosN = Math.abs((float) (vx * inv * nx + vy * inv * ny + vz * inv * nz));
					float det = (float) (cosN * receiverArea / d2);
					depositsUsed++;

					int bin = (int) (arrival / BIN_SECONDS);
					if (bin < BINS) {
						float sum = 0;
						for (int b = 0; b < bands; b++) {
							float e = rayEnergy[b] * det;
							energy[s][bin * bands + b] += e;
							sum += e;
						}
						dirX[s][bin] += arriveX * sum;
						dirY[s][bin] += arriveY * sum;
						dirZ[s][bin] += arriveZ * sum;
					} else {
						for (int b = 0; b < bands; b++) lateEnergy[s] += rayEnergy[b] * det;
					}
				}

				// новое направление: рассеяние или зеркало
				float scatter = 0;
				for (int b = 0; b < bands; b++) scatter += m.scattering[b] * rayEnergy[b];
				scatter = total > 0 ? scatter / total : 0.2f;

				if (nextFloat() < scatter) {
					float[] d = lambert(nx, ny, nz);
					dx = d[0]; dy = d[1]; dz = d[2];
				} else {
					float dot = dx * nx + dy * ny + dz * nz;
					dx -= 2 * dot * nx;
					dy -= 2 * dot * ny;
					dz -= 2 * dot * nz;
				}
				// чуть отступаем от поверхности
				px += dx * 1e-3; py += dy * 1e-3; pz += dz * 1e-3;
			}
		}

		bouncesUsed += totalBounces;
		travelTimeTotal += travelTime;
	}

	/** Забрать к себе результат чужого куска. */
	public void mergeFrom(Tracer other, int sourceCount) {
		for (int s = 0; s < sourceCount; s++) {
			float[] mine = energy[s], theirs = other.energy[s];
			for (int i = 0; i < mine.length; i++) mine[i] += theirs[i];
			for (int bin = 0; bin < BINS; bin++) {
				dirX[s][bin] += other.dirX[s][bin];
				dirY[s][bin] += other.dirY[s][bin];
				dirZ[s][bin] += other.dirZ[s][bin];
			}
			lateEnergy[s] += other.lateEnergy[s];
		}
		for (int b = 0; b < bands; b++) lossDb[b] += other.lossDb[b];
		raysUsed += other.raysUsed;
		bouncesUsed += other.bouncesUsed;
		depositsUsed += other.depositsUsed;
		travelTimeTotal += other.travelTimeTotal;
	}

	/** Свести статистику лучей во время реверберации и свободный пробег. */
	public void finish(float speedOfSound) {
		int totalBounces = bouncesUsed;
		meanFreePath = totalBounces > 0 ? (travelTimeTotal * speedOfSound) / totalBounces : 4f;
		float meanFreeTime = totalBounces > 0 ? travelTimeTotal / totalBounces : 0.02f;
		for (int b = 0; b < bands; b++) {
			float dbPerReflection = totalBounces > 0 ? lossDb[b] / totalBounces : 6f;
			float dbPerSecond = dbPerReflection / Math.max(1e-4f, meanFreeTime);
			rt60[b] = Math.min(12f, 60f / Math.max(0.5f, dbPerSecond));
		}
	}

	/* ------------------------------------------------------------------ */

	private final float[] lambertOut = new float[3];

	private float[] lambert(float nx, float ny, float nz) {
		float u = nextFloat(), v = nextFloat();
		float r = (float) Math.sqrt(u);
		float phi = (float) (2 * Math.PI * v);
		float x = r * (float) Math.cos(phi);
		float z = r * (float) Math.sin(phi);
		float y = (float) Math.sqrt(Math.max(0, 1 - u));
		float ux = 0, uy = 1, uz = 0;
		if (Math.abs(ny) > 0.9f) { ux = 1; uy = 0; }
		float tx = uy * nz - uz * ny, ty = uz * nx - ux * nz, tz = ux * ny - uy * nx;
		float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		if (tl < 1e-6f) tl = 1;
		tx /= tl; ty /= tl; tz /= tl;
		float bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
		float ox = tx * x + nx * y + bx * z;
		float oy = ty * x + ny * y + by * z;
		float oz = tz * x + nz * y + bz * z;
		float l = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
		if (l < 1e-6f) l = 1;
		lambertOut[0] = ox / l; lambertOut[1] = oy / l; lambertOut[2] = oz / l;
		return lambertOut;
	}

	/** Попадание луча в блок. */
	private static final class Ray {
		Materials material;
		float distance;
		float nx, ny, nz;
	}

	private final Ray ray = new Ray();

	/** Шаговый обход сетки до первого непустого блока. */
	private Ray march(VoxelSnapshot world, double ox, double oy, double oz,
	                  float dx, float dy, float dz, float maxDistance) {
		ray.material = null;
		ray.distance = maxDistance;

		int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double tDeltaX = Math.abs(1 / (dx == 0 ? 1e-9 : dx));
		double tDeltaY = Math.abs(1 / (dy == 0 ? 1e-9 : dy));
		double tDeltaZ = Math.abs(1 / (dz == 0 ? 1e-9 : dz));
		double tMaxX = (stepX > 0 ? (x + 1 - ox) : (ox - x)) * tDeltaX;
		double tMaxY = (stepY > 0 ? (y + 1 - oy) : (oy - y)) * tDeltaY;
		double tMaxZ = (stepZ > 0 ? (z + 1 - oz) : (oz - z)) * tDeltaZ;

		double travelled = 0;
		for (int guard = 0; guard < 256 && travelled < maxDistance; guard++) {
			float nx = 0, ny = 0, nz = 0;
			if (tMaxX < tMaxY) {
				if (tMaxX < tMaxZ) { x += stepX; travelled = tMaxX; tMaxX += tDeltaX; nx = -stepX; }
				else { z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ; nz = -stepZ; }
			} else {
				if (tMaxY < tMaxZ) { y += stepY; travelled = tMaxY; tMaxY += tDeltaY; ny = -stepY; }
				else { z += stepZ; travelled = tMaxZ; tMaxZ += tDeltaZ; nz = -stepZ; }
			}
			if (!world.inside(x, y, z)) break;
			byte id = world.local(x, y, z);
			// частичный блок отражает не весь луч: доля проходит мимо него насквозь
			if (id != VoxelSnapshot.AIR && nextFloat() > world.fill(x, y, z)) continue;
			if (id != VoxelSnapshot.AIR) {
				ray.material = Materials.values()[id];
				ray.distance = (float) travelled;
				ray.nx = nx; ray.ny = ny; ray.nz = nz;
				return ray;
			}
		}
		return ray;
	}

	/** Есть ли блок между двумя точками снимка. */
	private boolean blocked(VoxelSnapshot world, double ax, double ay, double az,
	                        double bx, double by, double bz) {
		double dx = bx - ax, dy = by - ay, dz = bz - az;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1e-6) return false;
		dx /= dist; dy /= dist; dz /= dist;
		Ray hit = march(world, ax, ay, az, (float) dx, (float) dy, (float) dz, (float) dist);
		return hit.material != null && hit.distance < dist - 1e-3;
	}
}
