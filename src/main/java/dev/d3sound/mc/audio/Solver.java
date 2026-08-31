package dev.d3sound.mc.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Фоновый расчёт акустики.
 *
 * На игровом потоке снимается куб блоков вокруг слушателя и список источников;
 * дальше всё считается здесь, в отдельном потоке, и результат публикуется
 * целиком — микшер всегда видит согласованное решение.
 *
 * Что считается:
 *   • обходные пути (волновой фронт по свободным клеткам) — дифракция;
 *   • отражения трассировкой с рассеянием — раннее эхо с направлениями;
 *   • хвост и время реверберации из статистики лучей.
 *
 * Тяжесть расчёта задаёт {@link Budget}: чем свободнее процессор, тем больше
 * лучей и глубже отражения.
 */
public final class Solver {
	public interface Job {
		VoxelSnapshot snapshot();
		int sourceCount();
		long sourceId(int i);
		double sourceX(int i);
		double sourceY(int i);
		double sourceZ(int i);
		float speedOfSound();
	}

	private final Budget budget;
	private final Paths paths;
	private final Tracer tracer = new Tracer();
	private final Map<Long, Solution> solutions = new ConcurrentHashMap<>();
	private final AtomicReference<Job> pending = new AtomicReference<>();
	private final float[] bandBuffer = new float[Materials.BAND_COUNT];
	private final float[] dirBuffer = new float[3];

	private volatile boolean running;
	private Thread thread;

	public volatile float[] rt60 = new float[Materials.BAND_COUNT];
	public volatile float meanFreePath = 4f;
	public volatile int lastSourceCount;
	public volatile long lastSolveAt;

	private static final float RECEIVER_RADIUS = 0.7f;

	/**
	 * Опорное расстояние, м.
	 *
	 * Источник звука не математическая точка: ближе этого расстояния громкость
	 * уже не растёт. Отсюда же и общий уровень — на опорном расстоянии звук
	 * звучит в полную силу, дальше падает как 1/r, по закону обратных квадратов
	 * для энергии.
	 */
	public static final float REFERENCE_DISTANCE = 1.6f;

	public Solver(Budget budget, int maxSnapshotSize) {
		this.budget = budget;
		this.paths = new Paths(maxSnapshotSize);
		java.util.Arrays.fill(rt60, 1f);
	}

	public void start() {
		if (running) return;
		running = true;
		thread = new Thread(this::loop, "D3Sound-solver");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
	}

	public void stop() {
		running = false;
		if (thread != null) thread.interrupt();
		thread = null;
		solutions.clear();
	}

	/** Отдать работу решателю (не блокирует игровой поток). */
	public void submit(Job job) { pending.set(job); }

	public Solution solutionFor(long id) { return solutions.get(id); }

	public void forget(long id) { solutions.remove(id); }

	public Budget budget() { return budget; }

	private void loop() {
		while (running) {
			Job job = pending.getAndSet(null);
			if (job == null) {
				try { Thread.sleep(4); } catch (InterruptedException e) { return; }
				continue;
			}
			try {
				long t0 = System.nanoTime();
				solve(job);
				budget.update((System.nanoTime() - t0) / 1e6f);
				lastSolveAt = System.currentTimeMillis();
			} catch (Throwable error) {
				// расчёт не должен ронять игру
				System.err.println("D3Sound: сбой расчёта: " + error);
			}
		}
	}

	private final double[] sxLocal = new double[Tracer.MAX_SOURCES];
	private final double[] syLocal = new double[Tracer.MAX_SOURCES];
	private final double[] szLocal = new double[Tracer.MAX_SOURCES];

	private void solve(Job job) {
		VoxelSnapshot world = job.snapshot();
		int count = Math.min(job.sourceCount(), Tracer.MAX_SOURCES);
		lastSourceCount = count;
		if (count == 0) return;

		float c = job.speedOfSound();
		for (int i = 0; i < count; i++) {
			sxLocal[i] = world.toLocalX(job.sourceX(i));
			syLocal[i] = world.toLocalY(job.sourceY(i));
			szLocal[i] = world.toLocalZ(job.sourceZ(i));
		}

		boolean diffraction = budget.diffraction;
		boolean reflections = budget.reflections;

		// 1. волновой фронт по свободным клеткам — обходные пути
		if (diffraction) {
			paths.build(world, world.listenerX, world.listenerY, world.listenerZ, world.radius * 1.8f);
		}

		// 2. отражения
		if (reflections) {
			tracer.trace(world, sxLocal, syLocal, szLocal, count,
				budget.rays(), budget.bounces(), c, RECEIVER_RADIUS, System.nanoTime());
			rt60 = tracer.rt60.clone();
			meanFreePath = tracer.meanFreePath;
		}

		int maxTaps = budget.taps();
		float scale = 1f / RECEIVER_RADIUS;

		for (int s = 0; s < count; s++) {
			Solution solution = new Solution();
			solution.reset();

			double dx = job.sourceX(s) - world.listenerX;
			double dy = job.sourceY(s) - world.listenerY;
			double dz = job.sourceZ(s) - world.listenerZ;
			float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

			// --- прямой путь или обход
			boolean blocked = blockedDirect(world, s);
			solution.directBlocked = blocked;
			if (!blocked) {
				float spread = spread(distance);
				for (int b = 0; b < Materials.BAND_COUNT; b++) bandBuffer[b] = spread;
				solution.addTap(distance / c, bandBuffer, (float) dx, (float) dy, (float) dz);
			} else {
				addDiffracted(world, job, s, c, solution);
			}

			// --- отражения: самые заметные бины становятся отводами
			addReflections(s, maxTaps, scale, c, solution);

			solution.tailLevel = (float) Math.sqrt(tracer.lateEnergy(s)) * scale;
			solutions.put(job.sourceId(s), solution);
		}
	}

	/** Геометрическое расхождение по амплитуде. */
	public static float spread(float distance) {
		return REFERENCE_DISTANCE / Math.max(REFERENCE_DISTANCE, distance);
	}

	/** Прямая видимость источника из точки слушателя. */
	private boolean blockedDirect(VoxelSnapshot world, int s) {
		double lx = world.toLocalX(world.listenerX);
		double ly = world.toLocalY(world.listenerY);
		double lz = world.toLocalZ(world.listenerZ);
		double dx = sxLocal[s] - lx, dy = syLocal[s] - ly, dz = szLocal[s] - lz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1e-6) return false;
		dx /= dist; dy /= dist; dz /= dist;

		int x = (int) Math.floor(lx), y = (int) Math.floor(ly), z = (int) Math.floor(lz);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double tdx = Math.abs(1 / (dx == 0 ? 1e-9 : dx));
		double tdy = Math.abs(1 / (dy == 0 ? 1e-9 : dy));
		double tdz = Math.abs(1 / (dz == 0 ? 1e-9 : dz));
		double tmx = (stepX > 0 ? (x + 1 - lx) : (lx - x)) * tdx;
		double tmy = (stepY > 0 ? (y + 1 - ly) : (ly - y)) * tdy;
		double tmz = (stepZ > 0 ? (z + 1 - lz) : (lz - z)) * tdz;

		double travelled = 0;
		for (int guard = 0; guard < 256 && travelled < dist - 0.05; guard++) {
			if (tmx < tmy) {
				if (tmx < tmz) { x += stepX; travelled = tmx; tmx += tdx; }
				else { z += stepZ; travelled = tmz; tmz += tdz; }
			} else {
				if (tmy < tmz) { y += stepY; travelled = tmy; tmy += tdy; }
				else { z += stepZ; travelled = tmz; tmz += tdz; }
			}
			if (travelled >= dist - 0.05) break;
			if (!world.inside(x, y, z)) return false;
			if (world.blocking(x, y, z)) return true;
		}
		return false;
	}

	/**
	 * Звук из-за преграды: идёт кратчайшим свободным путём, теряя на кромке
	 * тем больше, чем выше частота и чем длиннее крюк.
	 */
	private void addDiffracted(VoxelSnapshot world, Job job, int s, float c, Solution solution) {
		int lx = (int) Math.floor(sxLocal[s]);
		int ly = (int) Math.floor(syLocal[s]);
		int lz = (int) Math.floor(szLocal[s]);
		if (!paths.ready() || !world.inside(lx, ly, lz)) return;

		// источник может стоять вплотную к блоку — ищем ближайшую свободную клетку
		int index = -1;
		float best = Float.MAX_VALUE;
		for (int ox = -1; ox <= 1; ox++) {
			for (int oy = -1; oy <= 1; oy++) {
				for (int oz = -1; oz <= 1; oz++) {
					int x = lx + ox, y = ly + oy, z = lz + oz;
					if (!world.inside(x, y, z) || world.blocking(x, y, z)) continue;
					int i = world.index(x, y, z);
					float d = paths.distanceAt(i);
					if (d < best) { best = d; index = i; }
				}
			}
		}
		if (index < 0 || best == Float.MAX_VALUE) return;

		double dx = job.sourceX(s) - world.listenerX;
		double dy = job.sourceY(s) - world.listenerY;
		double dz = job.sourceZ(s) - world.listenerZ;
		float direct = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float pathLength = Math.max(direct, best);
		float delta = pathLength - direct;

		float spread = spread(pathLength);
		float sum = 0;
		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			float att = Paths.maekawaDb(delta, Materials.BANDS[b], c);
			bandBuffer[b] = spread * (float) Math.pow(10.0, -att / 20.0);
			sum += att;
		}
		solution.diffractionDb = sum / Materials.BAND_COUNT;

		paths.stepDirection(index, dirBuffer);
		if (dirBuffer[0] == 0 && dirBuffer[1] == 0 && dirBuffer[2] == 0) {
			solution.addTap(pathLength / c, bandBuffer, (float) dx, (float) dy, (float) dz);
		} else {
			// звук приходит с той стороны, куда уходит обходной путь
			solution.addTap(pathLength / c, bandBuffer, dirBuffer[0], dirBuffer[1], dirBuffer[2]);
		}
	}

	/** Самые сильные ранние приходы становятся отдельными отводами. */
	private void addReflections(int s, int maxTaps, float scale, float c, Solution solution) {
		int slots = Math.max(0, maxTaps - solution.tapCount);
		if (slots == 0) return;

		for (int slot = 0; slot < slots; slot++) {
			int bestBin = -1;
			float bestEnergy = 0;
			for (int bin = 0; bin < Tracer.BINS; bin++) {
				if (usedBin[bin]) continue;
				float sum = 0;
				for (int b = 0; b < Materials.BAND_COUNT; b++) sum += tracer.energyAt(s, bin, b);
				if (sum > bestEnergy) { bestEnergy = sum; bestBin = bin; }
			}
			if (bestBin < 0 || bestEnergy < 1e-10f) break;
			usedBin[bestBin] = true;

			for (int b = 0; b < Materials.BAND_COUNT; b++) {
				bandBuffer[b] = (float) Math.sqrt(tracer.energyAt(s, bestBin, b)) * scale;
			}
			tracer.direction(s, bestBin, dirBuffer);
			float delay = (bestBin + 0.5f) * Tracer.BIN_SECONDS;
			solution.addTap(delay, bandBuffer, dirBuffer[0], dirBuffer[1], dirBuffer[2]);
		}
		java.util.Arrays.fill(usedBin, false);
	}

	private final boolean[] usedBin = new boolean[Tracer.BINS];
}
