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
		/** Звук от удара по блоку: такой отдаёт в конструкцию куда больше. */
		boolean sourceImpact(int i);
		float speedOfSound();
	}

	private final Budget budget;
	private final Paths paths;
	private final Structure structure;
	private final Tracer tracer = new Tracer();
	private Tracer[] helpers = new Tracer[0];
	private java.util.concurrent.ExecutorService pool;
	private final Map<Long, Solution> solutions = new ConcurrentHashMap<>();
	private final AtomicReference<Job> pending = new AtomicReference<>();
	private final float[] bandBuffer = new float[Materials.BAND_COUNT];
	private final float[] dirBuffer = new float[3];

	private volatile boolean running;
	private Thread thread;

	public volatile float[] rt60 = new float[Materials.BAND_COUNT];
	public volatile float meanFreePath = 4f;
	/** Насколько место открытое: 0 — глухая коробка, 1 — чистое поле. */
	public volatile float openness = 1f;
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

	/**
	 * С какого перекрытия путь считается закрытым совсем.
	 *
	 * Ниже этого прямой звук ещё идёт, просто тише: сквозь листву, решётку,
	 * частокол слышно и должно быть слышно. Выше — считаем обход и проход
	 * сквозь преграду, потому что прямой доли уже практически не осталось.
	 */
	private static final float FULLY_BLOCKED = 0.75f;

	/**
	 * Сколько просачивается сквозь закрытую часть сечения, по полосам.
	 *
	 * Дырявая преграда для низа почти прозрачна: длинная волна её просто
	 * огибает. Верх же ловится каждой веткой и каждым столбиком.
	 */
	public static final float[] LEAK = {0.50f, 0.38f, 0.26f, 0.17f, 0.10f, 0.06f, 0.04f};

	/**
	 * Множитель прямого пути при частичном перекрытии.
	 *
	 * Свободная доля сечения проходит как есть, закрытая — только тем, что
	 * успевает просочиться, и тем хуже, чем выше частота. Вынесено наружу,
	 * чтобы игровой поток мог пересчитать то же самое по свежему перекрытию.
	 */
	public static float directFactor(float coverage, int band) {
		return (1 - coverage) + coverage * LEAK[band];
	}

	/** Дальше этих потерь структурный звук уже неслышен. */
	private static final float MAX_STRUCTURE_LOSS_DB = 55f;

	public Solver(Budget budget, int maxSnapshotSize) {
		this.budget = budget;
		this.paths = new Paths(maxSnapshotSize);
		this.structure = new Structure(maxSnapshotSize);
		java.util.Arrays.fill(rt60, 1f);
	}

	public void start() {
		if (running) return;
		// stop() уже сбросил running и обнулил ссылку, но старый поток мог ещё
		// доделывать solve(); дождёмся его, иначе решателей окажется два
		Thread previous = thread;
		if (previous != null && previous.isAlive()) {
			try { previous.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		}
		running = true;
		thread = new Thread(this::loop, "D3Sound-solver");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
	}

	public void stop() {
		running = false;
		if (thread != null) thread.interrupt();
		// ссылку не теряем: следующий start() по ней дождётся, пока поток выйдет
		if (pool != null) { pool.shutdownNow(); pool = null; }
		solutions.clear();
	}

	/** Посчитать прямо здесь и сейчас — для числового стенда. */
	public void solveNow(Job job) { solve(job); }

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

		// 2. звук по конструкции — им слышно соседей за стеной
		if (budget.structure) {
			structure.build(world, world.listenerX, world.listenerY, world.listenerZ, MAX_STRUCTURE_LOSS_DB);
		}

		// 3. отражения — лучи делятся между потоками по отведённой доле процессора
		if (reflections) {
			traceRays(world, count, c);
			rt60 = tracer.rt60.clone();
			meanFreePath = tracer.meanFreePath;
			openness = tracer.openness;
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
			float coverage = directCoverage(world, s);
			boolean blocked = coverage >= FULLY_BLOCKED;
			solution.directBlocked = blocked;
			solution.coverage = coverage;
			if (!blocked) {
				// путь перекрыт не до конца: так листва лишь слегка глушит,
				// а частокол глушит заметно, и ни то ни другое не пропадает целиком
				float spread = spread(distance);
				for (int b = 0; b < Materials.BAND_COUNT; b++) {
					bandBuffer[b] = spread * directFactor(coverage, b);
				}
				solution.addTap(distance / c, bandBuffer, (float) dx, (float) dy, (float) dz);
			} else if (diffraction) {
				addDiffracted(world, job, s, c, solution);
			} else if (budget.transmission) {
				// без расчёта обхода остаётся только то, что прошло насквозь
			} else {
				// без расчёта обхода звук за преградой просто глохнет
				float spread = spread(distance);
				for (int b = 0; b < Materials.BAND_COUNT; b++) {
					bandBuffer[b] = spread * (float) Math.pow(10.0, -(12 + 3.0 * b) / 20.0);
				}
				solution.addTap(distance / c, bandBuffer, (float) dx, (float) dy, (float) dz);
			}

			// --- звук, прошедший преграду насквозь: глухой, зато по прямой
			if (blocked && budget.transmission) addTransmitted(world, job, s, c, solution);

			// --- путь по самим блокам: им слышно соседей за стеной
			if (budget.structure) addStructureBorne(world, job, s, c, solution);

			// --- отражения: самые заметные бины становятся отводами
			if (reflections) {
				addReflections(s, maxTaps, scale, c, solution);
				// хвост тоже не может быть громче прямого звука
				solution.tailLevel = Math.min(spread(distance),
					(float) Math.sqrt(tracer.lateEnergy(s)) * scale);
			} else {
				solution.tailLevel = 0f;
			}
			solutions.put(job.sourceId(s), solution);
		}
	}

	/** Геометрическое расхождение по амплитуде. */
	public static float spread(float distance) {
		return REFERENCE_DISTANCE / Math.max(REFERENCE_DISTANCE, distance);
	}

	/**
	 * Пустить лучи, разделив их между потоками.
	 *
	 * Направления берутся из общей спирали по номеру луча, поэтому какой кусок
	 * кто посчитал — неважно: вместе они дают ровно то же покрытие сферы, что
	 * и один проход, только за меньшее время.
	 */
	private void traceRays(VoxelSnapshot world, int count, float c) {
		int rays = budget.rays();
		int bounces = budget.bounces();
		int threads = Math.max(1, Math.min(budget.threads(), rays / 32));
		long seed = System.nanoTime();

		if (threads <= 1) {
			tracer.trace(world, sxLocal, syLocal, szLocal, count, rays, bounces, c, RECEIVER_RADIUS, seed);
			return;
		}

		ensureHelpers(threads - 1);
		tracer.reset(count);
		int per = rays / threads;

		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads - 1);
		for (int t = 1; t < threads; t++) {
			final Tracer helper = helpers[t - 1];
			final int from = t * per;
			final int to = t == threads - 1 ? rays : (t + 1) * per;
			final long slice = seed + t * 0x9E3779B9L;
			helper.reset(count);
			pool.execute(() -> {
				try {
					helper.traceSlice(world, sxLocal, syLocal, szLocal, count, from, to, rays,
						bounces, c, RECEIVER_RADIUS, slice);
				} catch (Throwable error) {
					System.err.println("D3Sound: сбой в потоке лучей: " + error);
				} finally {
					done.countDown();
				}
			});
		}

		tracer.traceSlice(world, sxLocal, syLocal, szLocal, count, 0, per, rays,
			bounces, c, RECEIVER_RADIUS, seed);

		try {
			done.await();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		for (int t = 1; t < threads; t++) tracer.mergeFrom(helpers[t - 1], count);
		tracer.finish(c);
	}

	private void ensureHelpers(int needed) {
		if (helpers.length < needed) {
			Tracer[] grown = new Tracer[needed];
			System.arraycopy(helpers, 0, grown, 0, helpers.length);
			for (int i = helpers.length; i < needed; i++) grown[i] = new Tracer();
			helpers = grown;
		}
		if (pool == null) {
			pool = java.util.concurrent.Executors.newCachedThreadPool(r -> {
				Thread thread = new Thread(r, "D3Sound-rays");
				thread.setDaemon(true);
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
		}
	}

	/**
	 * Насколько плотно перекрыт прямой путь к источнику, 0…1.
	 *
	 * Раньше здесь был ответ «да/нет» по порогу в половину клетки, и он
	 * ошибался в обе стороны сразу: каменная ограда занимает лишь треть объёма
	 * и не перекрывала ничего, хотя поперёк неё не видно, — а листва с её
	 * пятой частью пропускала звук так, будто её нет. Теперь считается доля
	 * закрытого сечения, и каждая клетка спрашивается вдоль той оси, по которой
	 * луч в неё вошёл: плита не мешает идти вбок, но мешает идти сверху вниз.
	 */
	private float directCoverage(VoxelSnapshot world, int s) {
		double lx = world.toLocalX(world.listenerX);
		double ly = world.toLocalY(world.listenerY);
		double lz = world.toLocalZ(world.listenerZ);
		double dx = sxLocal[s] - lx, dy = syLocal[s] - ly, dz = szLocal[s] - lz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1e-6) return 0f;
		dx /= dist; dy /= dist; dz /= dist;

		int x = (int) Math.floor(lx), y = (int) Math.floor(ly), z = (int) Math.floor(lz);
		// клетки, в которых стоят сам источник и сам слушатель, преградой не
		// считаются: сундук, печь, дверь и нотный блок звучат из центра своего
		// блока, и без этого они были бы «перекрыты сами собой»
		final int hx = x, hy = y, hz = z;
		final int ex = (int) Math.floor(sxLocal[s]), ey = (int) Math.floor(syLocal[s]), ez = (int) Math.floor(szLocal[s]);
		int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double tdx = Math.abs(1 / (dx == 0 ? 1e-9 : dx));
		double tdy = Math.abs(1 / (dy == 0 ? 1e-9 : dy));
		double tdz = Math.abs(1 / (dz == 0 ? 1e-9 : dz));
		double tmx = (stepX > 0 ? (x + 1 - lx) : (lx - x)) * tdx;
		double tmy = (stepY > 0 ? (y + 1 - ly) : (ly - y)) * tdy;
		double tmz = (stepZ > 0 ? (z + 1 - lz) : (lz - z)) * tdz;

		double travelled = 0;
		float open = 1f;
		for (int guard = 0; guard < 256 && travelled < dist - 0.05; guard++) {
			int axis;
			if (tmx < tmy) {
				if (tmx < tmz) { x += stepX; travelled = tmx; tmx += tdx; axis = 0; }
				else { z += stepZ; travelled = tmz; tmz += tdz; axis = 2; }
			} else {
				if (tmy < tmz) { y += stepY; travelled = tmy; tmy += tdy; axis = 1; }
				else { z += stepZ; travelled = tmz; tmz += tdz; axis = 2; }
			}
			if (travelled >= dist - 0.05) break;
			if (!world.inside(x, y, z)) return 0f;
			if ((x == ex && y == ey && z == ez) || (x == hx && y == hy && z == hz)) continue;
			// вода прямому пути не мешает — сквозь неё слышно
			if (world.water(x, y, z)) continue;
			float cover = world.cover(axis, x, y, z);
			if (cover <= 0f) continue;
			// клетки перекрывают путь независимо: свободное сечение перемножается
			open *= 1f - Math.min(1f, cover);
			if (open <= 0.001f) return 1f;
		}
		return 1f - open;
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
					if (!world.inside(x, y, z) || !world.passable(x, y, z)) continue;
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
			float att = Paths.maekawaDb(delta, Materials.BANDS[b], c) * budget.diffractionGain;
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

	/**
	 * Звук, прошедший преграду насквозь.
	 *
	 * Это не обход и не вибрация по блокам, а третий, самый прямолинейный путь:
	 * волна давит на стену, стена колеблется как целое и переизлучает звук с
	 * другой стороны. Сколько при этом теряется, задаёт звукоизоляция R —
	 * величина справочная и очень разная: тонкая шерсть отдаёт почти всё, метр
	 * камня не отдаёт почти ничего.
	 *
	 * Толщина считается по закону массы: каждое удвоение даёт примерно +6 дБ,
	 * поэтому вторая стена добавляет заметно меньше первой. Тонкая, меньше
	 * блока, преграда (забор, стекло в раме) изолирует пропорционально тому,
	 * сколько её на пути.
	 *
	 * Слышится такой звук из стены и очень глухо: R растёт с частотой, так что
	 * от голоса за стеной остаётся один бубнёж — ровно то, что слышно в жизни.
	 */
	private void addTransmitted(VoxelSnapshot world, Job job, int s, float c, Solution solution) {
		double lx = world.toLocalX(world.listenerX);
		double ly = world.toLocalY(world.listenerY);
		double lz = world.toLocalZ(world.listenerZ);
		double dxl = sxLocal[s] - lx, dyl = syLocal[s] - ly, dzl = szLocal[s] - lz;
		double dist = Math.sqrt(dxl * dxl + dyl * dyl + dzl * dzl);
		if (dist < 1e-6) return;
		double ux = dxl / dist, uy = dyl / dist, uz = dzl / dist;

		java.util.Arrays.fill(worstR, 0f);
		float thickness = 0f;

		int x = (int) Math.floor(lx), y = (int) Math.floor(ly), z = (int) Math.floor(lz);
		final int hx = x, hy = y, hz = z;
		final int ex = (int) Math.floor(sxLocal[s]), ey = (int) Math.floor(syLocal[s]), ez = (int) Math.floor(szLocal[s]);
		int stepX = ux > 0 ? 1 : -1, stepY = uy > 0 ? 1 : -1, stepZ = uz > 0 ? 1 : -1;
		double tdx = Math.abs(1 / (ux == 0 ? 1e-9 : ux));
		double tdy = Math.abs(1 / (uy == 0 ? 1e-9 : uy));
		double tdz = Math.abs(1 / (uz == 0 ? 1e-9 : uz));
		double tmx = (stepX > 0 ? (x + 1 - lx) : (lx - x)) * tdx;
		double tmy = (stepY > 0 ? (y + 1 - ly) : (ly - y)) * tdy;
		double tmz = (stepZ > 0 ? (z + 1 - lz) : (lz - z)) * tdz;

		double travelled = 0;
		for (int guard = 0; guard < 512 && travelled < dist - 0.05; guard++) {
			if (tmx < tmy) {
				if (tmx < tmz) { x += stepX; travelled = tmx; tmx += tdx; }
				else { z += stepZ; travelled = tmz; tmz += tdz; }
			} else {
				if (tmy < tmz) { y += stepY; travelled = tmy; tmy += tdy; }
				else { z += stepZ; travelled = tmz; tmz += tdz; }
			}
			if (travelled >= dist - 0.05) break;
			if (!world.inside(x, y, z)) return;
			// свои клетки источника и слушателя стеной не считаем, вода — не преграда
			if ((x == ex && y == ey && z == ez) || (x == hx && y == hy && z == hz)) continue;
			byte id = world.local(x, y, z);
			if (id == VoxelSnapshot.AIR || id == VoxelSnapshot.WATER) continue;
			Materials m = Materials.values()[id];
			float fill = Math.max(0.05f, world.fill(x, y, z));
			thickness += fill;
			// изоляцию стены задаёт самый плотный её слой, остальное идёт в толщину
			for (int b = 0; b < Materials.BAND_COUNT; b++) {
				if (m.transmission[b] > worstR[b]) worstR[b] = m.transmission[b];
			}
		}
		if (thickness <= 0f) return;

		float spread = spread((float) dist);
		float gain = budget.transmissionGain;
		float sum = 0, level = 0;
		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			// закон массы: удвоение толщины — примерно +6 дБ; тоньше блока —
			// пропорционально тому, сколько преграды реально на пути
			float r = thickness >= 1f
				? worstR[b] + 6f * (float) (Math.log(thickness) / Math.log(2))
				: worstR[b] * thickness;
			bandBuffer[b] = spread * gain * (float) Math.pow(10.0, -r / 20.0);
			sum += r;
			level += bandBuffer[b];
		}
		solution.transmissionDb = sum / Materials.BAND_COUNT;
		if (level < 1e-5f) return;
		solution.transmissionTap = solution.addTap((float) dist / c, bandBuffer,
			(float) dxl, (float) dyl, (float) dzl);
	}

	/** Изоляция самого плотного слоя преграды по полосам, дБ. */
	private final float[] worstR = new float[Materials.BAND_COUNT];

	/**
	 * Звук, ушедший в конструкцию и вышедший из стены рядом со слушателем.
	 *
	 * Источник раскачивает ближайшую к нему поверхность (удар — сильно, голос
	 * по воздуху — слабо и только низом), волна идёт по блокам, теряя тем
	 * больше, чем выше частота и мягче материал, и излучается обратно в воздух
	 * той стеной, у которой стоит слушатель. Оттуда её и слышно.
	 */
	private void addStructureBorne(VoxelSnapshot world, Job job, int s, float c, Solution solution) {
		if (!structure.ready()) return;
		int sx = (int) Math.floor(sxLocal[s]);
		int sy = (int) Math.floor(syLocal[s]);
		int sz = (int) Math.floor(szLocal[s]);

		int best = -1;
		float bestCost = Float.MAX_VALUE;
		float bestGap = 1f;
		for (int ox = -2; ox <= 2; ox++) {
			for (int oy = -2; oy <= 2; oy++) {
				for (int oz = -2; oz <= 2; oz++) {
					int x = sx + ox, y = sy + oy, z = sz + oz;
					// вода не конструкция: раскачивать в ней нечего
					if (!world.inside(x, y, z) || !world.solid(x, y, z) || world.water(x, y, z)) continue;
					int i = world.index(x, y, z);
					if (!structure.reachable(i)) continue;
					float gap = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
					// чем дальше источник от поверхности, тем хуже он её раскачивает
					float cost = structure.lossAt(i) + 20f * (float) Math.log10(Math.max(1f, gap));
					if (cost < bestCost) { bestCost = cost; best = i; bestGap = gap; }
				}
			}
		}
		if (best < 0 || bestCost > MAX_STRUCTURE_LOSS_DB) return;

		float[] coupling = job.sourceImpact(s) ? Structure.IMPACT_COUPLING_DB : Structure.AIRBORNE_COUPLING_DB;
		float pathLoss = structure.lossAt(best);
		float gapLoss = 20f * (float) Math.log10(Math.max(1f, bestGap));
		float gain = budget.structureGain;

		float sum = 0;
		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			float db = pathLoss * Structure.BAND_FACTOR[b] + coupling[b] + Structure.RADIATION_DB[b] + gapLoss;
			bandBuffer[b] = gain * (float) Math.pow(10.0, -db / 20.0);
			sum += bandBuffer[b];
		}
		if (sum < 1e-4f) return;

		// по блокам звук идёт почти мгновенно — приходит раньше воздушного
		float delay = structure.lengthAt(best) / Structure.SPEED + bestGap / c;

		// слышится из той стены, что рядом со слушателем
		int emerge = structure.seedAt(best);
		int ex = emerge % world.size;
		int rest = emerge / world.size;
		int ez = rest % world.size;
		int ey = rest / world.size;
		float dx = (float) (world.originX + ex + 0.5 - world.listenerX);
		float dy = (float) (world.originY + ey + 0.5 - world.listenerY);
		float dz = (float) (world.originZ + ez + 0.5 - world.listenerZ);
		solution.structureTap = solution.addTap(delay, bandBuffer, dx, dy, dz);
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

			// путь длиной L не может прийти громче, чем тот же звук по прямой
			// на той же длине: отражение только теряет, приобрести ему негде
			float bound = spread((bestBin + 0.5f) * Tracer.BIN_SECONDS * c);
			for (int b = 0; b < Materials.BAND_COUNT; b++) {
				float level = (float) Math.sqrt(tracer.energyAt(s, bestBin, b)) * scale;
				bandBuffer[b] = Math.min(bound, level);
			}
			tracer.direction(s, bestBin, dirBuffer);
			float delay = (bestBin + 0.5f) * Tracer.BIN_SECONDS;
			solution.addTap(delay, bandBuffer, dirBuffer[0], dirBuffer[1], dirBuffer[2]);
		}
		java.util.Arrays.fill(usedBin, false);
	}

	private final boolean[] usedBin = new boolean[Tracer.BINS];
}
