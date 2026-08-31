package dev.d3sound.mc.bench;

import dev.d3sound.mc.audio.Air;
import dev.d3sound.mc.audio.Binaural;
import dev.d3sound.mc.audio.Budget;
import dev.d3sound.mc.audio.Materials;
import dev.d3sound.mc.audio.Mixer;
import dev.d3sound.mc.audio.Solution;
import dev.d3sound.mc.audio.Solver;
import dev.d3sound.mc.audio.Source;
import dev.d3sound.mc.audio.Structure;
import dev.d3sound.mc.audio.Tracer;
import dev.d3sound.mc.audio.VoxelSnapshot;

/**
 * Числовой стенд.
 *
 * Считает то же, что и движок в игре, но без единого звука в динамики: только
 * цифры и сверка их с тем, что говорит физика и справочники. Запускается
 * задачей {@code ./gradlew bench}.
 */
public final class Bench {
	private static int passed, failed;

	public static void main(String[] args) {
		air();
		binaural();
		room();
		occlusion();
		structure();
		mixer();

		System.out.printf("%nПройдено %d, провалено %d%n", passed, failed);
		if (failed > 0) System.exit(1);
	}

	/* ------------------------------------------------------------------ */

	private static void check(String what, double value, double expected, double tolerance, String unit) {
		boolean ok = Math.abs(value - expected) <= tolerance;
		System.out.printf("%-52s %8.3f %s (ждём %.3f ± %.3f) %s%n",
			what, value, unit, expected, tolerance, ok ? "ок" : "ПРОВАЛ");
		if (ok) passed++; else failed++;
	}

	private static void expect(String what, boolean ok, String detail) {
		System.out.printf("%-52s %-28s %s%n", what, detail, ok ? "ок" : "ПРОВАЛ");
		if (ok) passed++; else failed++;
	}

	/* --- воздух: сверка с таблицами ISO 9613-1 --- */

	private static void air() {
		System.out.println("\n== воздух ==");
		Air a = new Air(20, 50, 101.325f);
		check("скорость звука, 20 °C", a.speedOfSound, 343.2, 1.5, "м/с");
		check("затухание 1 кГц", a.dbPerMeter(3) * 1000, 4.7, 1.0, "дБ/км");
		check("затухание 4 кГц", a.dbPerMeter(5) * 1000, 29.7, 4.0, "дБ/км");
		check("затухание 8 кГц", a.dbPerMeter(6) * 1000, 105.0, 15.0, "дБ/км");

		check("скорость в воде", Air.WATER.speedOfSound, 1484, 1, "м/с");
		expect("вода почти не гасит низ", Air.WATER.dbPerMeter(0) * 100 < 0.01,
			String.format("%.5f дБ/100 м", Air.WATER.dbPerMeter(0) * 100));
		expect("в Нижнем мире звук быстрее", Air.nether().speedOfSound > a.speedOfSound + 20,
			String.format("%.0f м/с", Air.nether().speedOfSound));
		expect("в Энде медленнее", Air.end().speedOfSound < a.speedOfSound - 10,
			String.format("%.0f м/с", Air.end().speedOfSound));
	}

	/* --- бинауральная модель --- */

	private static void binaural() {
		System.out.println("\n== голова ==");
		Binaural b = new Binaural();
		Binaural.Ears e = new Binaural.Ears();
		Air air = new Air(20, 50, 101.325f);
		float[] flat = new float[Materials.BAND_COUNT];
		java.util.Arrays.fill(flat, 1f);

		b.compute(new float[]{0, 0, 1}, 5f, air, flat, e);
		check("спереди: разница задержек", (e.delayLeft - e.delayRight) * 1e6, 0, 1, "мкс");
		double frontL = level(e.gainLeft), frontR = level(e.gainRight);
		check("спереди: разница уровней", frontL - frontR, 0, 0.1, "дБ");
		expect("спереди звук не глушится", frontL > -1.5, String.format("%.2f дБ", frontL));

		b.compute(new float[]{1, 0, 0}, 5f, air, flat, e);
		check("справа: межушная задержка", (e.delayLeft - e.delayRight) * 1e6, 645, 60, "мкс");
		expect("справа правое ухо громче", level(e.gainRight) > level(e.gainLeft) + 4,
			String.format("%.1f дБ разницы", level(e.gainRight) - level(e.gainLeft)));

		b.compute(new float[]{-1, 0, 0}, 5f, air, flat, e);
		expect("слева зеркально", level(e.gainLeft) > level(e.gainRight) + 4,
			String.format("%.1f дБ разницы", level(e.gainLeft) - level(e.gainRight)));

		b.compute(new float[]{0, 0, -1}, 5f, air, flat, e);
		expect("сзади глуше, чем спереди", level(e.gainLeft) < frontL - 0.5,
			String.format("%.2f дБ", level(e.gainLeft) - frontL));

		b.delayScale = 2f;
		b.compute(new float[]{0, 0, 1}, 34.3f, air, flat, e);
		check("удвоенный доплер: задержка", e.delayLeft * 1000, 200.255, 1, "мс");
		b.delayScale = 1f;
	}

	private static double level(float[] gains) {
		double sum = 0;
		for (float g : gains) sum += g * g;
		return 10 * Math.log10(Math.max(1e-12, sum));
	}

	/* --- помещение: время реверберации против формулы Эйринга --- */

	private static void room() {
		System.out.println("\n== помещение ==");
		int radius = 16;
		VoxelSnapshot world = box(radius, 10, Materials.STONE);
		Tracer tracer = new Tracer();
		double[] sx = {world.toLocalX(world.listenerX) + 3};
		double[] sy = {world.toLocalY(world.listenerY)};
		double[] sz = {world.toLocalZ(world.listenerZ)};
		tracer.trace(world, sx, sy, sz, 1, 2048, 12, 343f, 0.7f, 12345L);

		// Эйринг для куба 10×10×10 из камня: α ≈ 0.02, объём 1000, площадь 600
		float alpha = Materials.STONE.absorption[2];
		double expected = 0.161 * 1000 / (-600 * Math.log(1 - alpha));
		check("RT60 500 Гц против Эйринга", tracer.rt60[2], expected, expected * 0.6, "с");
		check("средний свободный пробег", tracer.meanFreePath, 4 * 1000.0 / 600, 3.0, "м");
		expect("верх гаснет быстрее низа", tracer.rt60[6] <= tracer.rt60[0] + 1e-3,
			String.format("%.2f против %.2f с", tracer.rt60[6], tracer.rt60[0]));
		expect("энергия дошла до источника", tracer.lateEnergy(0) > 0 || anyEarly(tracer), "есть приходы");

		VoxelSnapshot soft = box(radius, 10, Materials.WOOL);
		Tracer t2 = new Tracer();
		t2.trace(soft, sx, sy, sz, 1, 2048, 12, 343f, 0.7f, 12345L);
		expect("в шерсти хвост короче, чем в камне", t2.rt60[2] < tracer.rt60[2],
			String.format("%.2f против %.2f с", t2.rt60[2], tracer.rt60[2]));
	}

	private static boolean anyEarly(Tracer tracer) {
		for (int bin = 0; bin < Tracer.BINS; bin++) {
			for (int b = 0; b < Materials.BAND_COUNT; b++) {
				if (tracer.energyAt(0, bin, b) > 0) return true;
			}
		}
		return false;
	}

	/* --- перекрытие и обход --- */

	private static void occlusion() {
		System.out.println("\n== преграды ==");
		int radius = 16;
		VoxelSnapshot world = box(radius, 12, Materials.STONE);
		int cx = (int) world.toLocalX(world.listenerX);
		int cy = (int) world.toLocalY(world.listenerY);
		int cz = (int) world.toLocalZ(world.listenerZ);

		Solution open = solve(world, world.listenerX + 4, world.listenerY, world.listenerZ, false);
		expect("прямой путь виден", !open.directBlocked, "не перекрыт");
		expect("прямой путь есть", open.tapCount > 0, open.tapCount + " отводов");

		// стена между слушателем и источником, но с проходом сверху
		for (int y = cy - 4; y <= cy + 2; y++) {
			for (int z = cz - 5; z <= cz + 5; z++) world.set(cx + 2, y, z, (byte) Materials.STONE.ordinal(), (byte) 100);
		}
		Solution walled = solve(world, world.listenerX + 4, world.listenerY, world.listenerZ, false);
		expect("за стеной прямой путь перекрыт", walled.directBlocked, "перекрыт");
		expect("звук всё равно доходит в обход", walled.tapCount > 0 && walled.diffractionDb > 0,
			String.format("%.1f дБ на кромке", walled.diffractionDb));
		expect("обход глуше прямого", energy(walled, 0) < energy(open, 0),
			String.format("%.1f дБ разницы", 20 * Math.log10(energy(walled, 0) / Math.max(1e-9, energy(open, 0)))));
		expect("верх теряется сильнее низа", walled.bands[0][6] < walled.bands[0][0],
			String.format("%.5f против %.5f", walled.bands[0][6], walled.bands[0][0]));
	}

	private static double energy(Solution s, int tap) {
		if (tap >= s.tapCount) return 0;
		double sum = 0;
		for (float g : s.bands[tap]) sum += g * g;
		return Math.sqrt(sum);
	}

	/* --- звук по конструкции --- */

	private static void structure() {
		System.out.println("\n== звук по блокам ==");
		{ // диагностика: есть ли вообще путь по конструкции
			VoxelSnapshot w = twoRooms(16, Materials.STONE);
			Structure st = new Structure(w.size);
			st.build(w, w.listenerX, w.listenerY, w.listenerZ, 55f);
			int lx = (int) w.toLocalX(w.listenerX), ly = (int) w.toLocalY(w.listenerY), lz = (int) w.toLocalZ(w.listenerZ);
			int sxi = lx + 5;
			System.out.printf("  диагностика: фронт готов=%s, пол под ногами=%s, пол под источником=%s%n",
				st.ready(), w.solid(lx, ly - 2, lz), w.solid(sxi, ly - 2, lz));
			if (w.solid(sxi, ly - 2, lz)) {
				int idx = w.index(sxi, ly - 2, lz);
				System.out.printf("  потери до пола под источником: %.2f дБ, достижим=%s%n",
					st.lossAt(idx), st.reachable(idx));
			}
		}
		double stoneLevel = 0, woolLevel = 0;
		for (Materials wall : new Materials[]{Materials.STONE, Materials.WOOD, Materials.WOOL}) {
			VoxelSnapshot world = twoRooms(16, wall);
			Solution through = solve(world, world.listenerX + 5, world.listenerY, world.listenerZ, true);
			int tap = through.structureTap;
			if (tap < 0) { expect("сквозь " + wall.label + ": путь по конструкции найден", false, "нет отвода"); continue; }
			double lowGain = through.bands[tap][0];
			double highGain = through.bands[tap][6];
			System.out.printf("  %-8s отвод %d из %d, низ %7.1f дБ, верх %7.1f дБ%n", wall.label, tap, through.tapCount,
				20 * Math.log10(Math.max(1e-9, lowGain)), 20 * Math.log10(Math.max(1e-9, highGain)));
			expect("сквозь " + wall.label + ": низ проходит лучше верха", lowGain > highGain * 4,
				String.format("в %.0f раз", lowGain / Math.max(1e-12, highGain)));
			if (wall == Materials.STONE) stoneLevel = lowGain;
			if (wall == Materials.WOOL) woolLevel = lowGain;
		}
		expect("через шерсть тише, чем через камень", woolLevel < stoneLevel,
			String.format("%.1f дБ разницы", 20 * Math.log10(Math.max(1e-12, woolLevel) / Math.max(1e-12, stoneLevel))));
	}

	/* --- микшер: нет ли NaN и адекватен ли уровень --- */

	private static void mixer() {
		System.out.println("\n== микшер ==");
		Mixer mixer = new Mixer();
		float[] pcm = new float[48000];
		for (int i = 0; i < pcm.length; i++) pcm[i] = (float) Math.sin(2 * Math.PI * 440 * i / 48000.0) * 0.5f;
		Source source = new Source(1, "test", pcm, 48000, true, false);
		source.x = 2; source.y = 0; source.z = 0;
		Source.Tap tap = source.taps[0];
		tap.targetDelayLeft = 0.006f;
		tap.targetDelayRight = 0.005f;
		for (int i = 0; i < 3; i++) { tap.targetGainLeft[i] = 0.5f; tap.targetGainRight[i] = 0.5f; }
		tap.active = true;
		source.targetSend = 0.3f;
		mixer.add(source);
		mixer.applyTail(new float[]{1.2f, 1.2f, 1.2f, 1.1f, 1f, 0.8f, 0.6f}, 6f);

		float[] l = new float[512], r = new float[512];
		double peak = 0;
		boolean finite = true;
		for (int block = 0; block < 200; block++) {
			mixer.render(l, r, 512);
			for (int i = 0; i < 512; i++) {
				if (!Float.isFinite(l[i]) || !Float.isFinite(r[i])) finite = false;
				peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
			}
		}
		expect("нет NaN и бесконечностей", finite, "чисто");
		expect("сигнал не пропал", peak > 0.05, String.format("пик %.3f", peak));
		expect("сигнал не перегружен", peak <= 1.0001, String.format("пик %.3f", peak));

		// хвост должен затухать, а не жить вечно
		source.stopping = true;
		mixer.render(l, r, 512);
		double before = rms(l, r);
		for (int block = 0; block < 400; block++) mixer.render(l, r, 512);
		double after = rms(l, r);
		expect("хвост затухает", after < before * 0.5 || before < 1e-6,
			String.format("%.6f → %.6f", before, after));
	}

	private static double rms(float[] l, float[] r) {
		double sum = 0;
		for (int i = 0; i < l.length; i++) sum += l[i] * l[i] + r[i] * r[i];
		return Math.sqrt(sum / (2.0 * l.length));
	}

	/* ------------------------------------------------------------------ */
	/*  синтетические миры                                                 */
	/* ------------------------------------------------------------------ */

	/** Пустая комната стороной side из заданного материала. */
	private static VoxelSnapshot box(int radius, int side, Materials material) {
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		int half = side / 2;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					boolean inside = Math.abs(x - c) < half && Math.abs(y - c) < half && Math.abs(z - c) < half;
					world.set(x, y, z, inside ? VoxelSnapshot.AIR : (byte) material.ordinal(), (byte) 100);
				}
			}
		}
		return world;
	}

	/**
	 * Две комнаты, разделённые стеной в один блок, слушатель стоит на полу
	 * рядом со стеной — так же, как игрок в игре.
	 */
	private static VoxelSnapshot twoRooms(int radius, Materials wall) {
		VoxelSnapshot world = box(radius, 14, Materials.STONE);
		int c = radius;
		for (int y = 0; y < world.size; y++) {
			for (int z = 0; z < world.size; z++) world.set(c + 2, y, z, (byte) wall.ordinal(), (byte) 100);
		}
		// пол под ногами: через него волна и уходит в стену
		for (int x = 0; x < world.size; x++) {
			for (int z = 0; z < world.size; z++) world.set(x, c - 2, z, (byte) wall.ordinal(), (byte) 100);
		}
		return world;
	}

	private static Solution solve(VoxelSnapshot world, double sxw, double syw, double szw, boolean impact) {
		Budget budget = new Budget();
		budget.manualQuality = 0.5f;
		budget.update(1f);
		Solver solver = new Solver(budget, world.size);
		final double[] pos = {sxw, syw, szw};
		Solver.Job job = new Solver.Job() {
			public VoxelSnapshot snapshot() { return world; }
			public int sourceCount() { return 1; }
			public long sourceId(int i) { return 7L; }
			public double sourceX(int i) { return pos[0]; }
			public double sourceY(int i) { return pos[1]; }
			public double sourceZ(int i) { return pos[2]; }
			public boolean sourceImpact(int i) { return impact; }
			public float speedOfSound() { return 343f; }
		};
		solver.solveNow(job);
		Solution solution = solver.solutionFor(7L);
		return solution == null ? new Solution() : solution;
	}
}
