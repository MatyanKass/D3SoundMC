package dev.d3sound.mc.audio;

import java.util.Arrays;

/**
 * Обходные пути звука — основа дифракции.
 *
 * Когда прямой видимости нет, звук не исчезает: он огибает кромки и приходит
 * из-за угла, через дверной проём, из-под потолка. Чтобы это посчитать, по
 * свободным клеткам снимка мира от слушателя строится волновой фронт
 * (алгоритм Дейкстры): для каждой клетки известны длина кратчайшего свободного
 * пути и направление первого шага.
 *
 * Отсюда берутся обе нужные величины:
 *   • удлинение пути δ — из него затухание на кромке по формуле Маекавы;
 *   • направление первого шага — с него звук и придёт в уши, поэтому за углом
 *     он слышится из-за угла, а не сквозь стену.
 *
 * Один проход обслуживает сразу все источники.
 */
public final class Paths {
	private static final int[] DX = {1, -1, 0, 0, 0, 0, 1, 1, 1, 1, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1, -1, -1, -1, -1};
	private static final int[] DY = {0, 0, 1, -1, 0, 0, 1, -1, 0, 0, 1, -1, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 1, 1, -1, -1};
	private static final int[] DZ = {0, 0, 0, 0, 1, -1, 0, 0, 1, -1, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1};
	private static final float[] COST = new float[26];

	static {
		for (int i = 0; i < 26; i++) {
			COST[i] = (float) Math.sqrt(DX[i] * DX[i] + DY[i] * DY[i] + DZ[i] * DZ[i]);
		}
	}

	/** Размер массивов: рассчитан на самый большой снимок. */
	private final int size;
	/** Размер текущего снимка — по нему и раскладывается индекс клетки. */
	private int grid;
	private final float[] dist;
	private final byte[] firstStep;
	private final int[] heap;
	private final float[] heapKey;
	private int heapSize;
	private int startIndex = -1;

	public Paths(int size) {
		this.size = size;
		int cells = size * size * size;
		this.dist = new float[cells];
		this.firstStep = new byte[cells];
		this.heap = new int[cells + 16];
		this.heapKey = new float[cells + 16];
	}

	public float distanceAt(int index) { return dist[index]; }

	public boolean reachable(int index) { return dist[index] < Float.MAX_VALUE; }

	/** Направление первого шага пути к клетке — с него приходит звук. */
	public void stepDirection(int index, float[] out) {
		int s = firstStep[index] & 0xFF;
		if (s >= 26) { out[0] = 0; out[1] = 0; out[2] = 0; return; }
		float len = COST[s];
		out[0] = DX[s] / len;
		out[1] = DY[s] / len;
		out[2] = DZ[s] / len;
	}

	/**
	 * Построить фронт от слушателя по свободным клеткам.
	 *
	 * @param maxDistance ограничение по длине пути, м (дальше считать незачем)
	 */
	public void build(VoxelSnapshot world, double lx, double ly, double lz, float maxDistance) {
		grid = world.size;
		Arrays.fill(dist, Float.MAX_VALUE);
		Arrays.fill(firstStep, (byte) 0xFF);
		heapSize = 0;

		int sx = (int) Math.floor(world.toLocalX(lx));
		int sy = (int) Math.floor(world.toLocalY(ly));
		int sz = (int) Math.floor(world.toLocalZ(lz));
		if (!world.inside(sx, sy, sz)) { startIndex = -1; return; }

		startIndex = world.index(sx, sy, sz);
		dist[startIndex] = 0;
		push(startIndex, 0);

		while (heapSize > 0) {
			float d = heapKey[0];
			int index = pop();
			if (d > dist[index]) continue;
			if (d > maxDistance) break;

			int lxi = index % grid;
			int rest = index / grid;
			int lzi = rest % grid;
			int lyi = rest / grid;
			byte step = firstStep[index];

			for (int n = 0; n < 26; n++) {
				int nx = lxi + DX[n], ny = lyi + DY[n], nz = lzi + DZ[n];
				if (!world.inside(nx, ny, nz)) continue;
				if (world.blocking(nx, ny, nz)) continue;
				int ni = world.index(nx, ny, nz);
				float nd = d + COST[n];
				if (nd >= dist[ni] || nd > maxDistance) continue;
				dist[ni] = nd;
				firstStep[ni] = index == startIndex ? (byte) n : step;
				push(ni, nd);
			}
		}
	}

	public boolean ready() { return startIndex >= 0; }

	/* --- двоичная куча на массивах: без аллокаций в горячем цикле --- */

	private void push(int index, float key) {
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

	/**
	 * Затухание на кромке по Маекаве: N = 2δ/λ — число Френеля,
	 * δ — насколько обходной путь длиннее прямого.
	 */
	public static float maekawaDb(float delta, float frequency, float speedOfSound) {
		if (delta <= 0) return 0f;
		double n = 2 * delta * frequency / speedOfSound;
		double x = Math.sqrt(2 * Math.PI * n);
		double att = 5 + 20 * Math.log10(x / Math.tanh(x));
		return (float) Math.min(25.0, Math.max(0.0, att));
	}
}
