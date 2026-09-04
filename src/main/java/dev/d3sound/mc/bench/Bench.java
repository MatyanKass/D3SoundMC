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
		outdoor();
		partialBlocks();
		throughWall();
		toggles();
		budget();
		coverage();
		water();
		insideBlock();
		streaming();
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
		// под водой глушит не среда, а ухо: канал залит, согласования нет
		double earLow = Math.pow(10, -Air.SUBMERGED_EAR_DB[0] / 20);
		double earHigh = Math.pow(10, -Air.SUBMERGED_EAR_DB[6] / 20);
		System.out.printf("  ухо под водой: низ %.2f, верх %.3f%n", earLow, earHigh);
		expect("под водой низ доходит до уха", earLow > 0.5,
			String.format("%.0f%% амплитуды", earLow * 100));
		expect("под водой верха почти не остаётся", earHigh < 0.05,
			String.format("%.1f%% амплитуды", earHigh * 100));
		boolean rising = true;
		for (int b = 1; b < Materials.BAND_COUNT; b++) {
			if (Air.SUBMERGED_EAR_DB[b] <= Air.SUBMERGED_EAR_DB[b - 1]) rising = false;
			if (Air.MOLTEN_EAR_DB[b] <= Air.MOLTEN_EAR_DB[b - 1]) rising = false;
		}
		expect("чем выше частота, тем сильнее глушит", rising, "монотонно");
		expect("в лаве глуше, чем в воде", Air.MOLTEN_EAR_DB[3] > Air.SUBMERGED_EAR_DB[3],
			String.format("%.0f против %.0f дБ", Air.MOLTEN_EAR_DB[3], Air.SUBMERGED_EAR_DB[3]));
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

		// система координат слушателя: конвенция Minecraft
		float[] f = new float[3];
		Binaural.toListenerFrame(1, 0, 0, 0f, 0f, f);
		expect("yaw=0: источник в +X слышен слева", f[0] < 0, String.format("вправо %.2f", f[0]));
		Binaural.toListenerFrame(0, 0, 1, 0f, 0f, f);
		check("yaw=0: источник в +Z прямо по курсу", f[2], 1, 1e-5, "");
		Binaural.toListenerFrame(-1, 0, 0, 90f, 0f, f);
		check("yaw=90: взгляд на -X, источник там же", f[2], 1, 1e-5, "");
		Binaural.toListenerFrame(0, -1, 0, 0f, 90f, f);
		check("pitch=90: смотрим вниз, источник снизу", f[2], 1, 1e-5, "");

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

	private static double totalEnergy(Tracer tracer) {
		double sum = tracer.lateEnergy(0);
		for (int bin = 0; bin < Tracer.BINS; bin++) {
			for (int b = 0; b < Materials.BAND_COUNT; b++) sum += tracer.energyAt(0, bin, b);
		}
		return sum;
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


	/* --- открытое место: берег, скала, небо над головой --- */

	private static void outdoor() {
		System.out.println();
		System.out.println("== открытое место ==");

		VoxelSnapshot open = outdoors(20, false);
		Solution far = solve(open, open.listenerX + 20, open.listenerY, open.listenerZ, false);
		double directFar = energy(far, 0);
		double loudest = 0;
		int loudestTap = 0;
		for (int t = 1; t < far.tapCount; t++) {
			double e = energy(far, t);
			if (e > loudest) { loudest = e; loudestTap = t; }
		}
		System.out.printf("  ровное поле: прямой %.4f, самое громкое отражение %.4f (отвод %d из %d), хвост %.4f%n",
			directFar, loudest, loudestTap, far.tapCount, far.tailLevel);
		expect("в поле прямой звук громче любого отражения", loudest < directFar,
			String.format("%.1f дБ разницы", 20 * Math.log10(Math.max(1e-9, loudest) / Math.max(1e-9, directFar))));
		expect("в поле хвоста почти нет", far.tailLevel < directFar,
			String.format("хвост %.4f, прямой %.4f", far.tailLevel, directFar));

		VoxelSnapshot cliff = stoneCanyon(30);
		Solution near = solve(cliff, cliff.listenerX + 8, cliff.listenerY, cliff.listenerZ, false);
		Tracer probe = new Tracer();
		probe.trace(cliff, new double[]{cliff.toLocalX(cliff.listenerX) + 8}, new double[]{cliff.toLocalY(cliff.listenerY)},
			new double[]{cliff.toLocalZ(cliff.listenerZ)}, 1, 2048, 12, 343f, 0.7f, 77L);
		System.out.printf("  каменный каньон: RT60 %.2f с, пробег %.1f м, открытость %.0f%%%n",
			probe.rt60[2], probe.meanFreePath, probe.openness * 100);
		expect("у каменной скалы под небом нет собора", probe.rt60[2] < 2f,
			String.format("RT60 %.2f с", probe.rt60[2]));
		expect("открытое место видно по улетевшим лучам", probe.openness > 0.3f,
			String.format("%.0f%% лучей ушло в небо", probe.openness * 100));

		// в закрытой коробке всё наоборот: лучи не улетают, хвост длинный
		VoxelSnapshot room = box(16, 12, Materials.STONE);
		Tracer inside = new Tracer();
		inside.trace(room, new double[]{room.toLocalX(room.listenerX) + 3}, new double[]{room.toLocalY(room.listenerY)},
			new double[]{room.toLocalZ(room.listenerZ)}, 1, 2048, 12, 343f, 0.7f, 77L);
		System.out.printf("  каменная комната: RT60 %.2f с, открытость %.0f%%%n", inside.rt60[2], inside.openness * 100);
		expect("в закрытом камне хвост остаётся длинным", inside.rt60[2] > 5f,
			String.format("RT60 %.2f с", inside.rt60[2]));
		expect("закрытая комната не считается открытой", inside.openness < 0.05f,
			String.format("%.1f%%", inside.openness * 100));

		Mixer wetTest = new Mixer();
		wetTest.applyTail(inside.rt60, inside.meanFreePath, inside.openness);
		float wetRoom = wetTest.wet();
		wetTest.applyTail(probe.rt60, probe.meanFreePath, probe.openness);
		float wetOpen = wetTest.wet();
		expect("на открытом месте хвоста подмешивается меньше", wetOpen < wetRoom * 0.3f,
			String.format("%.2f против %.2f", wetOpen, wetRoom));

		// далёкий источник: отражения не должны перекрикивать прямой звук
		Solution distant = solve(cliff, cliff.listenerX, cliff.listenerY, cliff.listenerZ + 25, false);
		double directFarCliff = energy(distant, 0);
		double loudFarCliff = 0;
		for (int t = 1; t < distant.tapCount; t++) loudFarCliff = Math.max(loudFarCliff, energy(distant, t));
		System.out.printf("  источник за 25 м: прямой %.4f, громчайшее отражение %.4f (%d отводов)%n",
			directFarCliff, loudFarCliff, distant.tapCount);
		expect("издалека отражение не громче прямого звука", loudFarCliff <= directFarCliff,
			String.format("%.1f дБ разницы", 20 * Math.log10(Math.max(1e-9, loudFarCliff) / Math.max(1e-9, directFarCliff))));
		double direct = energy(near, 0);
		double sum = 0;
		for (int t = 1; t < near.tapCount; t++) sum += energy(near, t);
		System.out.printf("  у скалы: прямой %.4f, сумма отражений %.4f, хвост %.4f%n", direct, sum, near.tailLevel);
		expect("у скалы отражения не громче прямого пути", sum < direct,
			String.format("%.1f дБ разницы", 20 * Math.log10(Math.max(1e-9, sum) / Math.max(1e-9, direct))));

		Budget budget = new Budget();
		budget.manualQuality = 0.5f;
		budget.update(1f);
		Tracer tracer = new Tracer();
		double[] sx = {open.toLocalX(open.listenerX) + 10};
		double[] sy = {open.toLocalY(open.listenerY)};
		double[] sz = {open.toLocalZ(open.listenerZ)};
		tracer.trace(open, sx, sy, sz, 1, 2048, 12, 343f, 0.7f, 4242L);
		System.out.printf("  время затухания в поле: %.2f с, свободный пробег %.1f м%n", tracer.rt60[2], tracer.meanFreePath);
		expect("под открытым небом эха нет", tracer.rt60[2] < 0.6f,
			String.format("RT60 %.2f с", tracer.rt60[2]));
	}

	/** Каменный берег со скалой — то же, что на скриншоте у игрока. */
	private static VoxelSnapshot stoneCanyon(int radius) {
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					boolean rock = y < c - 1 || x > c + 12;
					world.set(x, y, z, rock ? (byte) Materials.STONE.ordinal() : VoxelSnapshot.AIR, (byte) 100);
				}
			}
		}
		return world;
	}

	/** Ровная земля до горизонта; при cliff — ещё и каменная стена сбоку. */
	private static VoxelSnapshot outdoors(int radius, boolean cliff) {
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					world.set(x, y, z, y < c - 1 ? (byte) Materials.DIRT.ordinal() : VoxelSnapshot.AIR, (byte) 100);
				}
			}
		}
		if (cliff) {
			for (int y = c - 1; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					for (int x = c + 12; x < world.size; x++) {
						world.set(x, y, z, (byte) Materials.STONE.ordinal(), (byte) 100);
					}
				}
			}
		}
		return world;
	}

	/* --- неполные блоки: листва, ограда, плита --- */

	private static void partialBlocks() {
		System.out.println();
		System.out.println("== неполные блоки ==");

		// листва: пятая часть клетки, звук должен глохнуть чуть-чуть
		double leaves = directLevel(barrier(Materials.FOLIAGE, 20, 20, 20, 20, 1));
		double leaves3 = directLevel(barrier(Materials.FOLIAGE, 20, 20, 20, 20, 3));
		double free = directLevel(barrier(Materials.FOLIAGE, 0, 0, 0, 0, 0));
		System.out.printf("  открыто %.4f, за листвой %.4f, за тремя слоями %.4f%n", free, leaves, leaves3);
		expect("сквозь листву слышно", leaves > free * 0.5,
			String.format("%.1f дБ потерь", 20 * Math.log10(leaves / free)));
		expect("листва всё же глушит", leaves < free * 0.95,
			String.format("%.1f дБ потерь", 20 * Math.log10(leaves / free)));
		expect("чем гуще листва, тем глуше", leaves3 < leaves,
			String.format("%.1f против %.1f дБ", 20 * Math.log10(leaves3 / free), 20 * Math.log10(leaves / free)));

		// каменная ограда: объёма треть, но поперёк неё сплошная стена
		double fence = directLevel(barrier(Materials.STONE, 35, 95, 20, 95, 1));
		System.out.printf("  за каменной оградой %.4f%n", fence);
		expect("ограда перекрывает звук поперёк себя", fence < free * 0.25,
			String.format("%.1f дБ потерь", 20 * Math.log10(Math.max(1e-9, fence) / free)));
		expect("ограда глушит сильнее листвы", fence < leaves,
			String.format("%.4f против %.4f", fence, leaves));

		// плита: сверху вниз не пройти, а вбок над ней — сколько угодно
		double slabSide = directLevel(barrier(Materials.STONE, 50, 8, 100, 8, 1));
		System.out.printf("  вдоль плиты %.4f%n", slabSide);
		expect("вдоль плиты звук идёт свободно", slabSide > free * 0.85,
			String.format("%.1f дБ потерь", 20 * Math.log10(slabSide / free)));
	}

	/**
	 * Коридор вдоль оси X: слушатель в центре, источник за преградой из
	 * заданного числа слоёв. Перекрытие у преграды задаётся по осям отдельно.
	 */
	private static VoxelSnapshot barrier(Materials material, int volume, int alongX, int alongY, int alongZ, int layers) {
		int radius = 12;
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) world.set(x, y, z, VoxelSnapshot.AIR, (byte) 0);
			}
		}
		for (int n = 0; n < layers; n++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					world.set(c + 2 + n, y, z, (byte) material.ordinal(), (byte) volume,
						(byte) alongX, (byte) alongY, (byte) alongZ);
				}
			}
		}
		return world;
	}

	/** Уровень прямого (нулевого) отвода; отражения и прочее выключены. */
	private static double directLevel(VoxelSnapshot world) {
		Solution s = solveFull(world, world.originX + world.radius + 5.5,
			world.originY + world.radius + 0.5, world.originZ + world.radius + 0.5,
			false, false, false, false, false);
		return s.tapCount == 0 ? 0 : energy(s, 0);
	}

	/* --- за стенкой: огибание, проход насквозь и герметичность стен --- */

	private static void throughWall() {
		System.out.println();
		System.out.println("== за стенкой ==");

		VoxelSnapshot stone = openBox(20, Materials.STONE);
		Solution s = solve(stone, stone.originX + 25.5, stone.originY + 20.5, stone.originZ + 20.5, false);
		double direct = 5.0;   // слушатель в центре снимка, источник в пяти блоках
		float earliest = Float.MAX_VALUE;
		for (int t = 0; t < s.tapCount; t++) {
			if (t == s.structureTap) continue;   // по блокам звук идёт быстрее воздуха, это законно
			earliest = Math.min(earliest, s.delay[t]);
		}
		// отвод стоит в середине своего пятимиллисекундного бина, отсюда и допуск
		double shortest = (direct / 343.0) - 0.0026;
		System.out.printf("  коробка без крыши: отводов %d, огибание %.1f дБ, сквозь стену %.1f дБ%n",
			s.tapCount, s.diffractionDb, s.transmissionDb);
		expect("сквозь стену звук по воздуху не срезает путь", earliest >= shortest,
			String.format("самый ранний %.2f мс, прямой %.2f мс", earliest * 1000, direct / 343.0 * 1000));

		int around = s.transmissionTap == 0 ? 1 : 0;
		if (s.tapCount > around && around != s.structureTap) {
			double low = s.bands[around][0], high = s.bands[around][6];
			expect("из-за стены звук приходит глухим", low > high * 3,
				String.format("низ %.4f, верх %.4f", low, high));
		}

		expect("сквозь камень что-то проходит, но еле-еле", s.transmissionDb > 40,
			String.format("%.1f дБ изоляции", s.transmissionDb));

		VoxelSnapshot wool = openBox(20, Materials.WOOL);
		Solution soft = solve(wool, wool.originX + 25.5, wool.originY + 20.5, wool.originZ + 20.5, false);
		System.out.printf("  шерстяная коробка: сквозь стену %.1f дБ%n", soft.transmissionDb);
		expect("сквозь шерсть проходит куда легче, чем сквозь камень",
			soft.transmissionDb < s.transmissionDb - 20,
			String.format("%.1f против %.1f дБ", soft.transmissionDb, s.transmissionDb));

		double stoneLevel = s.transmissionTap >= 0 ? energy(s, s.transmissionTap) : 0;
		double woolLevel = soft.transmissionTap >= 0 ? energy(soft, soft.transmissionTap) : 0;
		expect("за шерстяной стеной слышно громче, чем за каменной", woolLevel > stoneLevel,
			String.format("%.5f против %.5f", woolLevel, stoneLevel));

		Solution off = solveFull(wool, wool.originX + 25.5, wool.originY + 20.5, wool.originZ + 20.5,
			false, true, true, true, false);
		diffractionStrength(stone, stone.originX + 25.5, stone.originY + 20.5, stone.originZ + 20.5);

		expect("проход сквозь стену выключается", off.transmissionTap < 0 && soft.transmissionTap >= 0,
			"отвод " + soft.transmissionTap);
	}

	/** Сила огибания: множитель потерь на кромке должен слышимо менять уровень. */
	private static void diffractionStrength(VoxelSnapshot world, double sx, double sy, double sz) {
		Solution normal = solveTuned(world, sx, sy, sz, 1f);
		Solution strong = solveTuned(world, sx, sy, sz, 2f);
		double a = energy(normal, 0), b = energy(strong, 0);
		System.out.printf("  огибание: 100%% → %.5f, 200%% → %.5f%n", a, b);
		expect("усиленное огибание глушит звук за преградой", b < a,
			String.format("%.1f дБ разницы", 20 * Math.log10(Math.max(1e-9, b) / Math.max(1e-9, a))));
	}

	private static Solution solveTuned(VoxelSnapshot world, double sxw, double syw, double szw, float diffractionGain) {
		Budget budget = new Budget();
		budget.manualQuality = 0.5f;
		budget.transmission = false;      // мерим именно огибание
		budget.structure = false;
		budget.reflections = false;
		budget.diffractionGain = diffractionGain;
		budget.update(1f);
		Solver solver = new Solver(budget, world.size);
		Solver.Job job = new Solver.Job() {
			public VoxelSnapshot snapshot() { return world; }
			public int sourceCount() { return 1; }
			public long sourceId(int i) { return 11L; }
			public double sourceX(int i) { return sxw; }
			public double sourceY(int i) { return syw; }
			public double sourceZ(int i) { return szw; }
			public boolean sourceImpact(int i) { return false; }
			public float speedOfSound() { return 343f; }
		};
		solver.solveNow(job);
		Solution solution = solver.solutionFor(11L);
		return solution == null ? new Solution() : solution;
	}

	/** Каменная коробка 5×5 без крыши в чистом поле; слушатель снаружи у стены. */
	private static VoxelSnapshot openBox(int radius, Materials wall) {
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					world.set(x, y, z, y < c - 1 ? (byte) Materials.DIRT.ordinal() : VoxelSnapshot.AIR, (byte) 100);
				}
			}
		}
		int bx = c + 5;
		for (int x = bx - 2; x <= bx + 2; x++) {
			for (int z = c - 2; z <= c + 2; z++) {
				for (int y = c - 1; y <= c + 1; y++) {
					boolean side = x == bx - 2 || x == bx + 2 || z == c - 2 || z == c + 2;
					if (side) world.set(x, y, z, (byte) wall.ordinal(), (byte) 100);
				}
			}
		}
		return world;
	}

	/* --- переключатели в настройках должны и правда что-то менять --- */

	private static void toggles() {
		System.out.println();
		System.out.println("== переключатели ==");
		VoxelSnapshot world = box(16, 12, Materials.STONE);
		int cx = (int) world.toLocalX(world.listenerX);
		int cy = (int) world.toLocalY(world.listenerY);
		int cz = (int) world.toLocalZ(world.listenerZ);
		for (int y = cy - 4; y <= cy + 2; y++) {
			for (int z = cz - 5; z <= cz + 5; z++) world.set(cx + 2, y, z, (byte) Materials.STONE.ordinal(), (byte) 100);
		}

		Solution all = solve(world, world.listenerX + 4, world.listenerY, world.listenerZ, false, true, true, true);
		Solution noReflections = solve(world, world.listenerX + 4, world.listenerY, world.listenerZ, false, true, false, true);
		Solution noDiffraction = solve(world, world.listenerX + 4, world.listenerY, world.listenerZ, false, false, true, true);
		Solution noStructure = solve(world, world.listenerX + 4, world.listenerY, world.listenerZ, false, true, true, false);

		expect("без отражений хвост пропадает", noReflections.tailLevel == 0f && all.tailLevel > 0f,
			String.format("%.4f против %.4f", noReflections.tailLevel, all.tailLevel));
		expect("без отражений отводов меньше", noReflections.tapCount < all.tapCount,
			noReflections.tapCount + " против " + all.tapCount);
		expect("без огибания преград кромка не считается", noDiffraction.diffractionDb == 0f && all.diffractionDb > 0f,
			String.format("%.1f против %.1f дБ", noDiffraction.diffractionDb, all.diffractionDb));
		expect("без огибания звук за преградой всё же слышен", energy(noDiffraction, 0) > 0, "есть отвод");
		expect("без звука через блоки такого отвода нет", noStructure.structureTap < 0 && all.structureTap >= 0,
			"отвод " + all.structureTap);
	}

	/* --- потолок процессора --- */

	private static void budget() {
		System.out.println();
		System.out.println("== нагрузка ==");
		Budget budget = new Budget();
		budget.ownShareLimit = 0.40f;
		budget.targetLoad = 1f;
		budget.panicLoad = 1f;

		float allowed = budget.allowedSolveMs();
		expect("разрешённая длина прогона выведена из доли",
			allowed > 4f && allowed <= budget.maxSolveMs,
			String.format("%.1f мс при %d ядрах", allowed, budget.cores()));

		for (int i = 0; i < 60; i++) budget.update(budget.allowedSolveMs() * 3f);
		float heavy = budget.quality();
		expect("перебор по процессору сбрасывает качество", heavy <= 0.2f,
			String.format("качество %.2f, доля %.1f%%", heavy, budget.ownShare() * 100));

		for (int i = 0; i < 200; i++) budget.update(0.2f);
		expect("на свободной машине качество растёт", budget.quality() > heavy,
			String.format("качество %.2f, доля %.1f%%", budget.quality(), budget.ownShare() * 100));

		// ручные дальность и период должны перебивать автоподбор
		Budget manual = new Budget();
		manual.manualRadius = 12;
		manual.manualIntervalMs = 220;
		expect("ручная дальность перебивает авто", manual.radius() == 12, manual.radius() + " блоков");
		expect("ручной период перебивает авто", manual.intervalMs() == 220, manual.intervalMs() + " мс");
		manual.manualRadius = 999;
		manual.manualIntervalMs = 1;
		expect("невменяемые значения зажимаются",
			manual.radius() <= 64 && manual.intervalMs() >= 20,
			manual.radius() + " блоков, " + manual.intervalMs() + " мс");
		manual.manualSources = 4;
		expect("ручное число источников перебивает авто", manual.sources() == 4,
			manual.sources() + " шт");
		manual.manualSources = 500;
		expect("больше, чем движок умеет, не запросить", manual.sources() <= Tracer.MAX_SOURCES,
			manual.sources() + " шт");

		Budget auto = new Budget();
		expect("ноль означает авто", auto.radius() >= 14 && auto.radius() <= 32,
			auto.radius() + " блоков");

		Budget tight = new Budget();
		tight.ownShareLimit = 0.02f;
		expect("маленький потолок — меньше потоков", tight.threads() < budget.threads() || budget.cores() < 4,
			String.format("%d против %d", tight.threads(), budget.threads()));
		expect("потоки не съедают все ядра", budget.threads() <= Math.max(1, budget.cores() - 1),
			String.format("%d из %d ядер", budget.threads(), budget.cores()));

		// куски лучей должны давать то же, что один проход
		VoxelSnapshot world = box(16, 10, Materials.STONE);
		double[] sx = {world.toLocalX(world.listenerX) + 3};
		double[] sy = {world.toLocalY(world.listenerY)};
		double[] sz = {world.toLocalZ(world.listenerZ)};
		Tracer whole = new Tracer();
		whole.trace(world, sx, sy, sz, 1, 1024, 10, 343f, 0.7f, 999L);

		Tracer part = new Tracer();
		Tracer other = new Tracer();
		part.reset(1);
		other.reset(1);
		part.traceSlice(world, sx, sy, sz, 1, 0, 512, 1024, 10, 343f, 0.7f, 999L);
		other.traceSlice(world, sx, sy, sz, 1, 512, 1024, 1024, 10, 343f, 0.7f, 999L);
		part.mergeFrom(other, 1);
		part.finish(343f);

		double one = totalEnergy(whole), split = totalEnergy(part);
		expect("расчёт кусками даёт то же, что целиком",
			Math.abs(one - split) < one * 0.35 + 1e-9,
			String.format("%.4g против %.4g", one, split));
		expect("время затухания при делении на куски то же",
			Math.abs(whole.rt60[2] - part.rt60[2]) < whole.rt60[2] * 0.25f,
			String.format("%.2f против %.2f с", whole.rt60[2], part.rt60[2]));
	}

	/* --- перекрытие спрашивается вдоль той оси, по которой идёт луч --- */

	private static void coverage() {
		System.out.println();
		System.out.println("== перекрытие по осям ==");
		VoxelSnapshot world = new VoxelSnapshot(8);
		world.setOrigin(0.5, 0.5, 0.5);
		// плита: сверху вниз сплошная, вбок почти пустая
		world.set(3, 3, 3, (byte) Materials.STONE.ordinal(), (byte) 50, (byte) 10, (byte) 100, (byte) 10);
		expect("вдоль плиты пути почти нет преграды", world.cover(0, 3, 3, 3) < 0.2f,
			String.format("%.2f", world.cover(0, 3, 3, 3)));
		expect("сверху плита перекрывает всё", world.cover(1, 3, 3, 3) > 0.9f,
			String.format("%.2f", world.cover(1, 3, 3, 3)));
		expect("преградой клетку делает самая закрытая ось", world.blocking(3, 3, 3),
			String.format("максимум %.2f", world.cover(3, 3, 3)));
		expect("объём остался объёмом", Math.abs(world.fill(3, 3, 3) - 0.5f) < 0.01f,
			String.format("%.2f", world.fill(3, 3, 3)));

		// пустая клетка ничего не перекрывает ни по одной оси
		expect("воздух не перекрывает ничего",
			world.cover(0, 4, 4, 4) == 0 && world.cover(1, 4, 4, 4) == 0 && world.cover(2, 4, 4, 4) == 0,
			"чисто");
	}

	/* --- вода: сквозь неё слышно --- */

	private static void water() {
		System.out.println();
		System.out.println("== вода ==");

		// слушатель в толще воды, источник в четырёх метрах над ним в воздухе
		int radius = 12;
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) {
					world.set(x, y, z, y <= c + 1 ? (byte) Materials.WATER.ordinal() : VoxelSnapshot.AIR,
						(byte) 100);
				}
			}
		}
		expect("вода проходима для звука", world.passable(c, c, c) && world.water(c, c, c), "проходима");
		Solution under = solve(world, world.listenerX, world.listenerY + 4, world.listenerZ, false);
		System.out.printf("  под водой: перекрытие %.2f, отводов %d%n", under.coverage, under.tapCount);
		expect("под водой прямой путь не перекрыт", !under.directBlocked,
			String.format("перекрытие %.2f", under.coverage));
		expect("под водой звук доходит", under.tapCount > 0, under.tapCount + " отводов");
	}

	/* --- источник внутри своего блока: сундук, печь, нотный блок --- */

	private static void insideBlock() {
		System.out.println();
		System.out.println("== источник в своём блоке ==");
		int radius = 12;
		VoxelSnapshot world = new VoxelSnapshot(radius);
		world.setOrigin(0.5, 0.5, 0.5);
		int c = radius;
		for (int x = 0; x < world.size; x++) {
			for (int y = 0; y < world.size; y++) {
				for (int z = 0; z < world.size; z++) world.set(x, y, z, VoxelSnapshot.AIR, (byte) 0);
			}
		}
		// одинокий каменный блок в трёх метрах — в нём и сидит источник
		world.set(c + 3, c, c, (byte) Materials.STONE.ordinal(), (byte) 100);
		Solution s = solve(world, world.listenerX + 3, world.listenerY, world.listenerZ, false);
		System.out.printf("  сундук в блоке: перекрытие %.2f, отводов %d%n", s.coverage, s.tapCount);
		expect("свой блок источника не глушит его самого", s.coverage < 0.75f && !s.directBlocked,
			String.format("перекрытие %.2f", s.coverage));

		// множитель частичного перекрытия доступен снаружи
		expect("множитель частичного перекрытия считается наружу",
			Math.abs(Solver.directFactor(0f, 3) - 1f) < 1e-6
				&& Solver.directFactor(1f, 3) == Solver.LEAK[3]
				&& Solver.directFactor(0.5f, 6) < Solver.directFactor(0.5f, 0),
			String.format("%.3f при половине перекрытия", Solver.directFactor(0.5f, 3)));
	}

	/* --- длинный звук, который подаётся порциями --- */

	private static void streaming() {
		System.out.println();
		System.out.println("== потоковый звук ==");

		int rate = 48000;
		// кольца на 0.25 с хватает, чтобы проверить оборот несколько раз
		Source source = Source.streaming(1L, "record", rate, false, 0.25f);
		Source.Tap tap = source.taps[0];
		for (int i = 0; i < 3; i++) { tap.targetGainLeft[i] = 1f / 3; tap.targetGainRight[i] = 1f / 3; }
		tap.active = true;
		source.tapCount = 1;

		expect("сначала данных нет", source.written() == 0, source.written() + " семплов");


		float[] l = new float[512], r = new float[512], bus = new float[512];

		// подаём ровную единицу порциями и сводим — курсор не должен убегать вперёд данных
		float[] chunk = new float[1024];
		java.util.Arrays.fill(chunk, 1f);
		double loudest = 0;
		int fed = 0;
        for (int block = 0; block < 60; block++) {
			if (source.written() - source.played() < 4096) { source.append(chunk, chunk.length); fed++; }
			java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f);
			source.mix(l, r, bus, 512, rate, 0.05f, 0.4f, 1f);
			for (int i = 0; i < 512; i++) loudest = Math.max(loudest, Math.abs(l[i]));
		}
		System.out.printf("  подано %d порций, всего %d семплов, проиграно %d%n",
			fed, source.written(), source.played());
		expect("курсор не обгоняет поданные данные", source.played() <= source.written(),
			source.played() + " из " + source.written());
		expect("звук из кольца доходит до выхода", loudest > 0.2,
			String.format("пик %.3f", loudest));
		expect("память не растёт вместе со звуком", source.pcm.length == rate / 4,
			source.pcm.length + " семплов в кольце при " + source.written() + " поданных");
		expect("источник ещё играет", !source.finished, "жив");

		// поток кончился — источник должен доиграть остаток и закрыться
		source.markComplete();
		boolean ended = false;
		for (int block = 0; block < 400 && !ended; block++) {
			source.mix(l, r, bus, 512, rate, 0.05f, 0.4f, 1f);
			ended = source.finished;
		}
		expect("после конца потока источник закрывается", ended, "закрылся");

		// молчащий поток не должен висеть вечно
		Source stuck = Source.streaming(2L, "stuck", rate, false, 0.25f);
		stuck.taps[0].active = true;
		stuck.tapCount = 1;
		boolean dropped = false;
		for (int block = 0; block < 2000 && !dropped; block++) {
			stuck.mix(l, r, bus, 512, rate, 0.05f, 0.4f, 1f);
			dropped = stuck.finished;
		}
		expect("зависший поток не живёт вечно", dropped, "снят");
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

		// тихий и средний сигнал ограничитель обязан пропускать как есть
		Mixer clean = new Mixer();
		float[] probe = new float[256];
		for (int i = 0; i < probe.length; i++) probe[i] = (float) Math.sin(2 * Math.PI * 200 * i / 48000.0) * 0.5f;
		Source plain = new Source(2, "clean", probe, 48000, true, false);
		Source.Tap direct = plain.taps[0];
		for (int i = 0; i < 3; i++) { direct.targetGainLeft[i] = 1f / 3; direct.targetGainRight[i] = 1f / 3; }
		direct.active = true;
		clean.setWet(0f);
		clean.add(plain);
		double worst = 0;
		for (int block = 0; block < 20; block++) {
			clean.render(l, r, 256);
			for (int i = 0; i < 256; i++) worst = Math.max(worst, Math.abs(l[i]));
		}
		// три полосы с одинаковым усилением складываются обратно в исходный сигнал
		double expectedPeak = 0.5 / 3;
		expect("ограничитель не трогает обычную громкость", Math.abs(worst - expectedPeak) < expectedPeak * 0.01,
			String.format("пик %.4f, ждём %.4f", worst, expectedPeak));
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
		return solve(world, sxw, syw, szw, impact, true, true, true);
	}

	private static Solution solve(VoxelSnapshot world, double sxw, double syw, double szw, boolean impact,
	                              boolean diffraction, boolean reflections, boolean structure) {
		return solveFull(world, sxw, syw, szw, impact, diffraction, reflections, structure, true);
	}

	private static Solution solveFull(VoxelSnapshot world, double sxw, double syw, double szw, boolean impact,
	                                  boolean diffraction, boolean reflections, boolean structure,
	                                  boolean transmission) {
		Budget budget = new Budget();
		budget.transmission = transmission;
		budget.manualQuality = 0.5f;
		budget.diffraction = diffraction;
		budget.reflections = reflections;
		budget.structure = structure;
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
