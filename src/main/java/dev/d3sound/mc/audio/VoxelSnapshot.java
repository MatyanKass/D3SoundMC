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

	public final int radius;
	public final int size;
	private final byte[] cells;
	/** Доля объёма клетки, занятая блоком: 0…100. Плита — 50, забор — около 10. */
	private final byte[] fills;

	/** Мировые координаты угла куба. */
	public int originX, originY, originZ;
	/** Позиция слушателя в момент снимка. */
	public double listenerX, listenerY, listenerZ;

	public VoxelSnapshot(int radius) {
		this.radius = radius;
		this.size = radius * 2;
		this.cells = new byte[size * size * size];
		this.fills = new byte[size * size * size];
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
		int i = index(lx, ly, lz);
		cells[i] = material;
		fills[i] = material == AIR ? 0 : fillPercent;
	}

	/** Насколько плотно клетка занята, 0…1. */
	public float fill(int lx, int ly, int lz) {
		if (!inside(lx, ly, lz)) return 0f;
		return fills[index(lx, ly, lz)] / 100f;
	}

	/**
	 * Считать ли клетку преградой для прямого звука.
	 *
	 * Плита и ступень перекрывают путь, а забор или решётка — нет: звук
	 * проходит между прутьями, теряя разве что немного верха.
	 */
	public boolean blocking(int lx, int ly, int lz) { return fill(lx, ly, lz) >= 0.5f; }

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
