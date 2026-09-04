package dev.d3sound.mc.audio;

/**
 * Снимок блоков вокруг слушателя.
 *
 * Мир игры нельзя читать из чужого потока, а решателю нужен произвольный доступ
 * к тысячам блоков. Поэтому раз в несколько тиков куб вокруг слушателя
 * переписывается в плоский массив материалов — дальше вся акустика считается по
 * нему, в фоне и без блокировок.
 *
 * Заодно это быстрее: обращение к массиву вместо поиска чанка и состояния блока.
 */
public final class VoxelSnapshot {
	public static final byte AIR = -1;
	/** Материал воды — звук сквозь неё идёт, поэтому у него отдельное место. */
	public static final byte WATER = (byte) Materials.WATER.ordinal();

	public final int radius;
	public final int size;
	private final byte[] cells;
	/** Доля объёма клетки, занятая блоком: 0…100. Плита — 50, забор — около 10. */
	private final byte[] fills;
	/**
	 * Насколько блок закрывает клетку, если смотреть вдоль каждой оси: 0…100.
	 *
	 * Одной доли объёма для звука мало. Каменная ограда занимает от силы треть
	 * клетки, но поперёк неё не видно ничего — она сплошная во всю высоту, и
	 * горизонтальный звук она перекрывает так же, как целый блок. Наоборот,
	 * плита занимает половину объёма, но сверху вниз сквозь неё не пройти, а
	 * вбок над ней — сколько угодно. Поэтому храним три проекции, и луч
	 * спрашивает ту, вдоль которой идёт.
	 */
	private final byte[] coverX, coverY, coverZ;

	/** Мировые координаты угла куба. */
	public int originX, originY, originZ;
	/** Позиция слушателя в момент снимка. */
	public double listenerX, listenerY, listenerZ;

	public VoxelSnapshot(int radius) {
		this.radius = radius;
		this.size = radius * 2;
		this.cells = new byte[size * size * size];
		this.fills = new byte[size * size * size];
		this.coverX = new byte[size * size * size];
		this.coverY = new byte[size * size * size];
		this.coverZ = new byte[size * size * size];
	}

	public void setOrigin(double lx, double ly, double lz) {
		listenerX = lx; listenerY = ly; listenerZ = lz;
		originX = (int) Math.floor(lx) - radius;
		originY = (int) Math.floor(ly) - radius;
		originZ = (int) Math.floor(lz) - radius;
	}

	public byte[] cells() { return cells; }

	public int index(int lx, int ly, int lz) { return (ly * size + lz) * size + lx; }

	public boolean inside(int lx, int ly, int lz) {
		return lx >= 0 && ly >= 0 && lz >= 0 && lx < size && ly < size && lz < size;
	}

	/** Материал по локальным координатам куба. */
	public byte local(int lx, int ly, int lz) {
		if (!inside(lx, ly, lz)) return AIR;
		return cells[index(lx, ly, lz)];
	}

	/** Материал по мировым координатам блока. */
	public byte world(int wx, int wy, int wz) {
		return local(wx - originX, wy - originY, wz - originZ);
	}

	public void set(int lx, int ly, int lz, byte material) {
		set(lx, ly, lz, material, (byte) 100);
	}

	/** Материал и доля занятого объёма — от неё зависит, пройдёт ли звук мимо. */
	public void set(int lx, int ly, int lz, byte material, byte fillPercent) {
		set(lx, ly, lz, material, fillPercent, fillPercent, fillPercent, fillPercent);
	}

	/** То же, но с отдельным перекрытием по каждой оси. */
	public void set(int lx, int ly, int lz, byte material, byte fillPercent,
	                byte alongX, byte alongY, byte alongZ) {
		int i = index(lx, ly, lz);
		cells[i] = material;
		boolean air = material == AIR;
		fills[i] = air ? 0 : fillPercent;
		coverX[i] = air ? 0 : alongX;
		coverY[i] = air ? 0 : alongY;
		coverZ[i] = air ? 0 : alongZ;
	}

	/** Насколько плотно клетка занята, 0…1. */
	public float fill(int lx, int ly, int lz) {
		if (!inside(lx, ly, lz)) return 0f;
		return fills[index(lx, ly, lz)] / 100f;
	}

	/**
	 * Какую часть клетки блок закрывает для луча, идущего вдоль оси.
	 *
	 * @param axis 0 — вдоль X, 1 — вдоль Y, 2 — вдоль Z
	 */
	public float cover(int axis, int lx, int ly, int lz) {
		if (!inside(lx, ly, lz)) return 0f;
		int i = index(lx, ly, lz);
		byte value = axis == 0 ? coverX[i] : axis == 1 ? coverY[i] : coverZ[i];
		return value / 100f;
	}

	/** Самое сильное перекрытие из трёх — им пользуются, когда направление неважно. */
	public float cover(int lx, int ly, int lz) {
		if (!inside(lx, ly, lz)) return 0f;
		int i = index(lx, ly, lz);
		return Math.max(coverX[i], Math.max(coverY[i], coverZ[i])) / 100f;
	}

	/**
	 * Считать ли клетку преградой для прямого звука.
	 *
	 * Плита и ступень перекрывают путь, а забор или решётка — нет: звук
	 * проходит между прутьями, теряя разве что немного верха.
	 */
	public boolean blocking(int lx, int ly, int lz) { return cover(lx, ly, lz) >= 0.5f; }

	/** Вода — это клетка, сквозь которую звук идёт, а не преграда. */
	public boolean water(int lx, int ly, int lz) { return local(lx, ly, lz) == WATER; }

	/**
	 * Можно ли пройти сквозь клетку.
	 *
	 * Воздух — очевидно; вода — тоже: под водой прямой путь и обход никуда не
	 * деваются, звук в ней идёт даже лучше, чем в воздухе. Отражаться от
	 * поверхности воды это не мешает — тем занимается трассировка.
	 */
	public boolean passable(int lx, int ly, int lz) {
		return water(lx, ly, lz) || !blocking(lx, ly, lz);
	}

	public boolean solid(int lx, int ly, int lz) { return local(lx, ly, lz) != AIR; }

	public Materials material(byte id) {
		return id == AIR ? null : Materials.values()[id];
	}

	/** Точка внутри куба (в мировых координатах)? */
	public boolean covers(double wx, double wy, double wz) {
		int lx = (int) Math.floor(wx) - originX;
		int ly = (int) Math.floor(wy) - originY;
		int lz = (int) Math.floor(wz) - originZ;
		return inside(lx, ly, lz);
	}

	public double toLocalX(double wx) { return wx - originX; }
	public double toLocalY(double wy) { return wy - originY; }
	public double toLocalZ(double wz) { return wz - originZ; }
}
