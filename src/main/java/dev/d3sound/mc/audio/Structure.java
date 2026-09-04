package dev.d3sound.mc.audio;

import java.util.Arrays;

/**
 * Структурный звук — тот, что идёт не по воздуху, а по самим блокам.
 *
 * Это ровно то, из-за чего слышно соседей за стеной: голос почти не проходит
 * сквозь перегородку по воздуху (там 40–50 дБ потерь), зато раскачивает саму
 * стену, а она уже излучает звук в соседнюю комнату. Поэтому такой звук
 * приходит глухим — верх гибнет в материале, — и слышится он из стены, а не
 * со стороны источника.
 *
 * Считается так же, как обходные пути, только фронт идёт по твёрдым клеткам,
 * а копится не длина, а потери в дБ: камень почти не гасит, дерево гасит
 * заметно, шерсть и земля глушат почти сразу. На стыке разных материалов
 * добавляются потери на скачке сопротивления.
 *
 * Фронт строится от слушателя: одного прохода хватает на все источники.
 */
public final class Structure {
	private static final int[] DX = {1, -1, 0, 0, 0, 0};
	private static final int[] DY = {0, 0, 1, -1, 0, 0};
	private static final int[] DZ = {0, 0, 0, 0, 1, -1};

	/** Потери на 500 Гц, дБ на метр материала. */
	private static float dampingPerMetre(Materials material) {
		return switch (material) {
			case METAL -> 0.10f;
			case STONE, BRICK -> 0.35f;
			case GLASS -> 0.80f;
			case WOOD -> 1.20f;
			case WATER -> 2.00f;
			case SAND, DIRT -> 4.00f;
			case SNOW, SOFT, WOOL, FOLIAGE -> 12.0f;
		};
	}

	/** Потери на стыке разных материалов: часть волны отражается назад. */
	private static final float JUNCTION_DB = 3f;

	/** Во сколько раз полоса гаснет быстрее опорных 500 Гц. */
	public static final float[] BAND_FACTOR = {0.45f, 0.70f, 1.0f, 1.6f, 2.6f, 4.2f, 6.0f};

	/**
	 * Потери при передаче звука из воздуха в конструкцию, дБ.
	 *
	 * Воздух лёгкий, стена тяжёлая, поэтому раскачать её звуком трудно и с
	 * ростом частоты всё труднее: до стены доходит в основном низ.
	 */
	public static final float[] AIRBORNE_COUPLING_DB = {18, 20, 24, 30, 37, 45, 55};

	/**
	 * То же для удара по блоку.
	 *
	 * Шаг, кирка, поршень, взрыв бьют по конструкции напрямую, без воздушной
	 * прослойки, — сюда уходит несравнимо больше энергии.
	 */
	public static final float[] IMPACT_COUPLING_DB = {4, 5, 7, 10, 14, 19, 25};

	/** Потери при излучении из конструкции обратно в воздух, дБ. */
	public static final float[] RADIATION_DB = {8, 8, 10, 13, 17, 22, 28};

	/** Скорость звука в конструкции, м/с: по блокам он идёт почти мгновенно. */
	public static final float SPEED = 3200f;

	/** Размер массивов: рассчитан на самый большой снимок. */
	private final int size;
	/** Размер текущего снимка — по нему и раскладывается индекс клетки. */
	private int grid;
	private final float[] loss;      // дБ на 500 Гц
	private final float[] length;    // пройденный путь, м
	private final int[] seed;        // клетка входа рядом со слушателем
	private int[] heap;
	private float[] heapKey;
	private int heapSize;
	private boolean ready;

	public Structure(int size) {
		this.size = size;
		int cells = size * size * size;
		this.loss = new float[cells];
		this.length = new float[cells];
		this.seed = new int[cells];
		this.heap = new int[cells + 16];
		this.heapKey = new float[cells + 16];
	}

	public boolean ready() { return ready; }

	public float lossAt(int index) { return loss[index]; }

	public float lengthAt(int index) { return length[index]; }

	public int seedAt(int index) { return seed[index]; }

	public boolean reachable(int index) { return loss[index] < Float.MAX_VALUE; }

	/**
	 * Построить фронт по твёрдым клеткам от всего, к чему прижат слушатель:
	 * пол под ногами, стены и потолок вокруг.
	 *
	 * @param maxLossDb дальше считать бессмысленно — это уже неслышно
	 */
	public void build(VoxelSnapshot world, double lx, double ly, double lz, float maxLossDb) {
		grid = world.size;
		Arrays.fill(loss, Float.MAX_VALUE);
		Arrays.fill(length, 0f);
		heapSize = 0;
		ready = false;

		int cx = (int) Math.floor(world.toLocalX(lx));
		int cy = (int) Math.floor(world.toLocalY(ly));
		int cz = (int) Math.floor(world.toLocalZ(lz));
		if (!world.inside(cx, cy, cz)) return;

		// затравка: все твёрдые клетки в шаге от слушателя — через них звук и выйдет
		for (int ox = -2; ox <= 2; ox++) {
			for (int oy = -2; oy <= 2; oy++) {
				for (int oz = -2; oz <= 2; oz++) {
					int x = cx + ox, y = cy + oy, z = cz + oz;
					// вода тоже «не воздух», но конструкцией она не является:
					// по ней структурный звук не идёт
					if (!world.inside(x, y, z) || !world.solid(x, y, z) || world.water(x, y, z)) continue;
					float gap = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
					int i = world.index(x, y, z);
					// чем дальше поверхность от уха, тем тише её отдача в воздух
					float start = 20f * (float) Math.log10(Math.max(1f, gap));
					if (start >= loss[i]) continue;
					loss[i] = start;
					length[i] = 0f;
					seed[i] = i;
					push(i, start);
				}
			}
		}
		if (heapSize == 0) return;
		ready = true;

		while (heapSize > 0) {
			float d = heapKey[0];
			int index = pop();
			if (d > loss[index]) continue;
			if (d > maxLossDb) break;

			int x = index % grid;
			int rest = index / grid;
			int z = rest % grid;
			int y = rest / grid;
			Materials here = world.material(world.local(x, y, z));
			if (here == null) continue;
			float damping = dampingPerMetre(here);

			for (int n = 0; n < 6; n++) {
				int nx = x + DX[n], ny = y + DY[n], nz = z + DZ[n];
				if (!world.inside(nx, ny, nz)) continue;
				byte id = world.local(nx, ny, nz);
				if (id == VoxelSnapshot.AIR || id == VoxelSnapshot.WATER) continue;
				Materials next = Materials.values()[id];
				// пористый блок и держится хуже, и передаёт хуже
				float fill = Math.max(0.1f, world.fill(nx, ny, nz));
				float step = 0.5f * damping + 0.5f * dampingPerMetre(next) / fill;
				if (next != here) step += JUNCTION_DB;
				float nd = d + step;
				int ni = world.index(nx, ny, nz);
				if (nd >= loss[ni] || nd > maxLossDb) continue;
				loss[ni] = nd;
				length[ni] = length[index] + 1f;
				seed[ni] = seed[index];
				push(ni, nd);
			}
		}
	}

	/* --- двоичная куча на массивах --- */

	private void push(int index, float key) {
		// ленивое удаление: одна клетка может попасть в кучу несколько раз,
		// поэтому запаса «по клетке на каждую» не хватает — растём вдвое
		if (heapSize == heap.length) {
			heap = Arrays.copyOf(heap, heapSize * 2);
			heapKey = Arrays.copyOf(heapKey, heapSize * 2);
		}
		int i = heapSize++;
		heap[i] = index;
		heapKey[i] = key;
		while (i > 0) {
			int parent = (i - 1) >> 1;
			if (heapKey[parent] <= heapKey[i]) break;
			swap(i, parent);
			i = parent;
		}
	}

	private int pop() {
		int top = heap[0];
		heapSize--;
		if (heapSize > 0) {
			heap[0] = heap[heapSize];
			heapKey[0] = heapKey[heapSize];
			int i = 0;
			while (true) {
				int l = i * 2 + 1, r = l + 1, best = i;
				if (l < heapSize && heapKey[l] < heapKey[best]) best = l;
				if (r < heapSize && heapKey[r] < heapKey[best]) best = r;
				if (best == i) break;
				swap(i, best);
				i = best;
			}
		}
		return top;
	}

	private void swap(int a, int b) {
		int ti = heap[a]; heap[a] = heap[b]; heap[b] = ti;
		float tk = heapKey[a]; heapKey[a] = heapKey[b]; heapKey[b] = tk;
	}
}
