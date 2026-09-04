package dev.d3sound.mc.client;

import com.mojang.blaze3d.audio.Channel;
import dev.d3sound.mc.audio.Air;
import dev.d3sound.mc.audio.Binaural;
import dev.d3sound.mc.audio.Budget;
import dev.d3sound.mc.audio.Materials;
import dev.d3sound.mc.audio.Mixer;
import dev.d3sound.mc.audio.Solution;
import dev.d3sound.mc.audio.Solver;
import dev.d3sound.mc.audio.Source;
import dev.d3sound.mc.audio.Tracer;
import dev.d3sound.mc.audio.VoxelSnapshot;
import dev.d3sound.mc.client.mixin.SoundBufferAccessor;
import dev.d3sound.mc.client.mixin.SoundEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Связка нашего движка с игрой.
 *
 * Разделение труда:
 *   • игровой поток снимает куб блоков вокруг слушателя (по кусочкам, чтобы не
 *     дёргать кадры) и раздаёт источникам готовые пути;
 *   • фоновый решатель считает дифракцию и отражения по снимку;
 *   • аудиопоток только сводит — там нет ни мира, ни аллокаций.
 */
public final class D3SoundEngine {
	public static final Logger LOG = LoggerFactory.getLogger("D3Sound");
	private static final D3SoundEngine INSTANCE = new D3SoundEngine();

	public static D3SoundEngine get() { return INSTANCE; }

	private D3SoundEngine() {}

	public volatile boolean enabled = D3Config.get().enabled;
	public volatile boolean verbose = false;

	private final Mixer mixer = new Mixer();
	private final Budget budget = new Budget();
	private final Solver solver = new Solver(budget, 72);
	private final Binaural binaural = new Binaural();
	private final Binaural.Ears ears = new Binaural.Ears();
	private final float[] dirBuffer = new float[3];
	private final float[] bandBuffer = new float[Materials.BAND_COUNT];

	private final Map<SoundInstance, Source> playing = new ConcurrentHashMap<>();
	private final Map<Identifier, Pcm> pcmCache = new ConcurrentHashMap<>();
	/**
	 * Звуки, которые мы забрали, но ещё не запустили.
	 *
	 * Распаковка идёт в другом потоке, и за это время игра успевает звук
	 * остановить. Раньше останавливать было нечего — источника ещё нет, — и
	 * колбэк потом всё равно его запускал: сломанный проигрыватель продолжал
	 * играть пластинку до конца, и снять её было нечем.
	 */
	private final java.util.Set<SoundInstance> pending = ConcurrentHashMap.newKeySet();
	private final AtomicLong nextId = new AtomicLong(1);

	private SoundEngine soundEngine;
	private ChannelAccess.ChannelHandle output;
	private ScheduledExecutorService pump;
	/** Канал уже заказан: заказ выполняется в другом потоке, дублировать нельзя. */
	private volatile boolean openingOutput;
	private int tickCounter;
	/** Слушатель под водой — тогда меняется и среда, и переход через поверхность. */
	private boolean listenerUnderwater;
	/** Голова в лаве: звук вязкий и медленный, верхов не остаётся вовсе. */
	private boolean listenerInLava;

	// снимок мира набирается слоями, чтобы не собирать 200 тысяч блоков за раз
	private VoxelSnapshot filling;
	private VoxelSnapshot ready;
	private VoxelSnapshot spare;
	private int fillLayer;
	private long lastSubmit;

	private record Pcm(float[] samples, int sampleRate) {}

	public Mixer mixer() { return mixer; }
	public Budget budget() { return budget; }
	public Solver solver() { return solver; }
	public int activeSources() { return playing.size(); }

	/** Сколько звучит источников, у которых есть место в мире. */
	private int positional() {
		int count = 0;
		for (Source s : playing.values()) if (!s.relative) count++;
		return count;
	}

	/* ------------------------------------------------------------------ */
	/*  перехват                                                           */
	/* ------------------------------------------------------------------ */

	public boolean takeOver(SoundEngine engine, SoundInstance instance,
	                        net.minecraft.client.resources.sounds.Sound sound,
	                        float volume, float pitch, boolean looping) {
		if (!enabled) return false;
		// музыка и эмбиент звучат «в голове», а не из точки мира: физике
		// распространения их подвергать нечему, но среда вокруг на них влияет
		boolean local = instance.isRelative() || instance.getAttenuation() == SoundInstance.Attenuation.NONE;
		if (local && localAmount <= 0f) return false;
		// предел считаем только по звукам мира: музыка геометрию не занимает,
		// и терять её из-за того, что вокруг шумно, незачем
		if (!local && positional() >= Tracer.MAX_SOURCES) return false;

		this.soundEngine = engine;
		solver.start();
		pending.add(instance);

		Identifier path = sound.getPath();
		if (sound.shouldStream()) {
			// длинный звук целиком в память не берём: подаём его порциями
			startStreaming(engine, instance, path, volume, pitch, looping);
			return true;
		}

		Pcm cached = pcmCache.get(path);
		if (cached != null) {
			pending.remove(instance);
			start(instance, cached, volume, pitch, looping);
			return true;
		}

		SoundEngineAccessor accessor = (SoundEngineAccessor) engine;
		accessor.d3sound$soundBuffers().getCompleteBuffer(path).thenAccept(buffer -> {
			Pcm pcm = decode(buffer);
			if (pcm == null) { pending.remove(instance); return; }
			pcmCache.put(path, pcm);
			// звук могли остановить, пока мы распаковывали
			if (!pending.remove(instance)) return;
			start(instance, pcm, volume, pitch, looping);
		}).exceptionally(error -> {
			pending.remove(instance);
			LOG.warn("D3Sound: нет семплов {}: {}", path, error.toString());
			return null;
		});
		return true;
	}

	private void start(SoundInstance instance, Pcm pcm, float volume, float pitch, boolean looping) {
		Source source = new Source(nextId.getAndIncrement(), instance.getIdentifier().toString(),
			pcm.samples(), pcm.sampleRate(), looping, instance.isRelative());
		source.x = instance.getX();
		source.y = instance.getY();
		source.z = instance.getZ();
		source.volume = volume;
		source.pitch = pitch;
		source.impact = isImpact(source.name);
		// пока решатель не ответил — простой прямой путь, чтобы звук не пропал
		applyFallback(source);
		playing.put(instance, source);
		mixer.add(source);
		if (verbose) LOG.info("D3Sound: {} на ({}, {}, {})", source.name, (int) source.x, (int) source.y, (int) source.z);
	}

	/**
	 * Похоже ли на удар по блоку.
	 *
	 * Шаг, кирка, поршень, падение и взрыв бьют по конструкции напрямую и
	 * уходят в неё почти целиком; голос или пластинка отдают в стену лишь ту
	 * малость, что успевает её раскачать через воздух.
	 */
	private static boolean isImpact(String id) {
		return id.contains("step") || id.contains("break") || id.contains("place")
			|| id.contains("hit") || id.contains("fall") || id.contains("land")
			|| id.contains("explo") || id.contains("anvil") || id.contains("piston")
			|| id.contains("dig") || id.contains("door") || id.contains("chest");
	}

	public void stop(SoundInstance instance) {
		pending.remove(instance);
		Source source = playing.remove(instance);
		if (source != null) {
			source.stopping = true;
			solver.forget(source.id);
		}
	}

	public void stopAll() {
		pending.clear();
		for (Source s : playing.values()) { s.stopping = true; solver.forget(s.id); }
		playing.clear();
	}

	/** Сколько звука держим позади курсора: хватает на любое эхо и на подкачку. */
	private static final float STREAM_BUFFER_SECONDS = 2.5f;
	/** Насколько стараемся идти впереди воспроизведения. */
	private static final float STREAM_AHEAD_SECONDS = 1.0f;

	private ExecutorService streamPump;

	/**
	 * Длинный звук — пластинка, музыка, долгий фон.
	 *
	 * Раньше такие звуки отдавались ванильному движку целиком, и это была дыра:
	 * проигрыватель за стеной было слышно так, будто стены нет, — ведь у игры
	 * никакой физики распространения нет. Теперь и они идут через нас.
	 *
	 * Целиком в память класть их нельзя: пластинка это десятки мегабайт, да и
	 * ждать её распаковки пришлось бы секунду. Поэтому звук читается из потока
	 * порциями в кольцевой буфер источника — играть начинает сразу, а памяти
	 * уходит столько же, сколько на короткий звук.
	 */
	private void startStreaming(SoundEngine engine, SoundInstance instance, Identifier path,
	                            float volume, float pitch, boolean looping) {
		SoundEngineAccessor accessor = (SoundEngineAccessor) engine;
		// повтор берёт на себя сам поток: он просто не кончается
		accessor.d3sound$soundBuffers().getStream(path, looping).thenAccept(stream -> {
			// пока поток открывался, звук могли остановить
			if (!pending.remove(instance)) {
				try { stream.close(); } catch (Exception ignored) { }
				return;
			}
			AudioFormat format = stream.getFormat();
			int rate = (int) format.getSampleRate();
			Source source = Source.streaming(nextId.getAndIncrement(), instance.getIdentifier().toString(),
				rate, instance.isRelative(), STREAM_BUFFER_SECONDS);
			source.x = instance.getX();
			source.y = instance.getY();
			source.z = instance.getZ();
			source.volume = volume;
			source.pitch = pitch;
			source.impact = false;
			applyFallback(source);
			playing.put(instance, source);
			mixer.add(source);
			if (verbose) LOG.info("D3Sound: поток {} на ({}, {}, {})", source.name,
				(int) source.x, (int) source.y, (int) source.z);
			pumpStream(stream, source, format);
		}).exceptionally(error -> {
			pending.remove(instance);
			LOG.warn("D3Sound: поток {} не открылся: {}", path, error.toString());
			return null;
		});
	}

	/** Качать порции, пока источник жив и поток не кончился. */
	private void pumpStream(AudioStream stream, Source source, AudioFormat format) {
		if (streamPump == null) {
			streamPump = Executors.newCachedThreadPool(r -> {
				Thread thread = new Thread(r, "D3Sound-stream");
				thread.setDaemon(true);
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
		}
		final int rate = (int) format.getSampleRate();
		final int channels = format.getChannels();
		final int bytesPerFrame = channels * format.getSampleSizeInBits() / 8;
		final long ahead = Math.round(rate * STREAM_AHEAD_SECONDS);
		final int chunkBytes = Math.max(bytesPerFrame, Math.round(rate * 0.1f) * bytesPerFrame);

		streamPump.execute(() -> {
			float[] scratch = new float[Math.round(rate * 0.1f) + 16];
			try (AudioStream open = stream) {
				while (!source.finished && !source.stopping) {
					if (source.written() - source.played() > ahead) {
						Thread.sleep(20);
						continue;
					}
					ByteBuffer data = open.read(chunkBytes);
					if (data == null || !data.hasRemaining()) { source.markComplete(); return; }
					// read отдаёт столько, сколько получилось, а не сколько просили:
					// декодер дочитывает пакет до конца. Если не растянуть буфер,
					// хвост каждой порции пропадёт — и звук пойдёт с дырками
					int available = data.remaining() / bytesPerFrame;
					if (scratch.length < available) scratch = new float[available];
					int frames = toMono(data, format, scratch);
					if (frames <= 0) { source.markComplete(); return; }
					source.append(scratch, frames);
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (Throwable error) {
				LOG.warn("D3Sound: поток {} оборвался: {}", source.name, error.toString());
			} finally {
				source.markComplete();
			}
		});
	}

	/** Порция из потока игры в моно-семплы нашего микшера. */
	private static int toMono(ByteBuffer data, AudioFormat format, float[] out) {
		int channels = format.getChannels();
		int bits = format.getSampleSizeInBits();
		ByteBuffer view = data.duplicate().order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		if (bits == 16) {
			ShortBuffer shorts = view.asShortBuffer();
			int frames = Math.min(out.length, shorts.remaining() / channels);
			for (int i = 0; i < frames; i++) {
				float sum = 0;
				for (int c = 0; c < channels; c++) sum += shorts.get(i * channels + c) / 32768f;
				out[i] = sum / channels;
			}
			return frames;
		}
		if (bits == 8) {
			int frames = Math.min(out.length, view.remaining() / channels);
			for (int i = 0; i < frames; i++) {
				float sum = 0;
				for (int c = 0; c < channels; c++) sum += ((view.get(i * channels + c) & 0xFF) - 128) / 128f;
				out[i] = sum / channels;
			}
			return frames;
		}
		return 0;
	}

	private static Pcm decode(com.mojang.blaze3d.audio.SoundBuffer buffer) {
		ByteBuffer data = ((SoundBufferAccessor) buffer).d3sound$data();
		if (data == null) return null;
		AudioFormat format = buffer.format();
		int channels = format.getChannels();
		int bits = format.getSampleSizeInBits();
		int rate = (int) format.getSampleRate();
		ByteBuffer view = data.duplicate().order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		view.rewind();

		if (bits == 16) {
			ShortBuffer shorts = view.asShortBuffer();
			int frames = shorts.remaining() / channels;
			float[] out = new float[frames];
			for (int i = 0; i < frames; i++) {
				float sum = 0;
				for (int c = 0; c < channels; c++) sum += shorts.get(i * channels + c) / 32768f;
				out[i] = sum / channels;
			}
			return new Pcm(out, rate);
		}
		if (bits == 8) {
			int frames = view.remaining() / channels;
			float[] out = new float[frames];
			for (int i = 0; i < frames; i++) {
				float sum = 0;
				for (int c = 0; c < channels; c++) sum += ((view.get(i * channels + c) & 0xFF) - 128) / 128f;
				out[i] = sum / channels;
			}
			return new Pcm(out, rate);
		}
		return null;
	}

	/* ------------------------------------------------------------------ */
	/*  кадр                                                               */
	/* ------------------------------------------------------------------ */

	public void updateListener(double x, double y, double z, float yawDeg, float pitchDeg) {
		mixer.listenerX = x;
		mixer.listenerY = y;
		mixer.listenerZ = z;
		mixer.listenerYaw = yawDeg;
		mixer.listenerPitch = pitchDeg;
	}

	public void clientTick(Minecraft client) {
		if (!enabled) return;
		ensureOutput();
		Level level = client.level;
		if (level == null) return;

		if (client.player != null) {
			updateListener(client.player.getX(), client.player.getEyeY(), client.player.getZ(),
				client.player.getYRot(), client.player.getXRot());
		}

		applyConfig();
		listenerUnderwater = client.player != null && client.player.isEyeInFluid(FluidTags.WATER);
		listenerInLava = client.player != null && client.player.isEyeInFluid(FluidTags.LAVA);

		if (++tickCounter % 20 == 0) mixer.air = airOf(level);

		fillSnapshot(level);
		applySolutions(level);
		mixer.applyTail(solver.rt60, solver.meanFreePath, solver.openness);
		D3Config config = D3Config.get();
		// ручная реверберация тоже знает про открытое небо: на берегу возвращаться неоткуда
		if (config.reverb > 0) mixer.setWet(config.reverb / 100f * (1f - 0.92f * solver.openness));
	}

	/** Настройки игрока в параметры расчёта. */
	private void applyConfig() {
		D3Config config = D3Config.get();
		enabled = config.enabled;
		budget.manualQuality = config.quality > 0 ? config.quality / 100f : -1f;
		budget.ownShareLimit = Math.max(0.02f, config.cpuShare / 100f);
		budget.targetLoad = config.cpuHeadroom / 100f;
		budget.panicLoad = Math.min(0.98f, budget.targetLoad + 0.18f);
		budget.diffraction = config.diffraction;
		budget.reflections = config.reflections;
		budget.structure = config.structure;
		budget.structureGain = config.structureLevel / 100f;
		budget.transmission = config.transmission;
		budget.transmissionGain = config.transmissionLevel / 100f;
		budget.manualRadius = config.range;
		budget.manualIntervalMs = config.updateMs;
		budget.manualSources = config.maxSources;
		budget.diffractionGain = config.diffractionLevel / 100f;
		localAmount = config.localAmbience / 100f;
		binaural.delayScale = Math.max(0f, config.doppler / 100f);
		mixer.setMasterGain(config.gain / 100f);
	}

	/**
	 * Среда вокруг слушателя.
	 *
	 * Под водой звук идёт вчетверо быстрее и почти не гаснет; в Нижнем мире
	 * воздух горячий и сухой, отчего скорость выше почти на треть; в Энде —
	 * холодная разрежённая пустота, звук медленный и вязкий.
	 */
	private Air airOf(Level level) {
		if (listenerInLava) return Air.LAVA;
		if (listenerUnderwater) return Air.WATER;
		var type = level.dimensionTypeRegistration().unwrapKey().orElse(null);
		if (type == BuiltinDimensionTypes.NETHER) return Air.nether();
		if (type == BuiltinDimensionTypes.END) return Air.end();
		float temp = level.getBiome(BlockPos.containing(mixer.listenerX, mixer.listenerY, mixer.listenerZ))
			.value().getBaseTemperature();
		return Air.forWeather(level.isRaining(), level.isThundering() && temp < 0.15f, temp);
	}

	/* --- снимок мира слоями --- */

	private void fillSnapshot(Level level) {
		int radius = budget.radius();
		if (filling == null || filling.radius != radius) {
			filling = new VoxelSnapshot(radius);
			spare = null;
			fillLayer = 0;
			filling.setOrigin(mixer.listenerX, mixer.listenerY, mixer.listenerZ);
		}
		if (fillLayer == 0) filling.setOrigin(mixer.listenerX, mixer.listenerY, mixer.listenerZ);

		int size = filling.size;
		int layers = Math.max(1, size / 6);          // весь куб за ~6 тиков
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int n = 0; n < layers && fillLayer < size; n++, fillLayer++) {
			int wy = filling.originY + fillLayer;
			for (int lz = 0; lz < size; lz++) {
				int wz = filling.originZ + lz;
				for (int lx = 0; lx < size; lx++) {
					int wx = filling.originX + lx;
					BlockState state = level.getBlockState(pos.set(wx, wy, wz));
					byte id = VoxelSnapshot.AIR;
					byte fill = 100, cx = 100, cy = 100, cz = 100;
					if (!state.isAir()) {
						if (state.liquid()) id = (byte) Materials.WATER.ordinal();
						else if (state.blocksMotion()) {
							Materials material = materialOf(state.getSoundType());
							Shape shape = shapeOf(state, level, pos);
							float d = material.density();
							id = (byte) material.ordinal();
							fill = scale(shape.volume(), d);
							cx = scale(shape.alongX(), d);
							cy = scale(shape.alongY(), d);
							cz = scale(shape.alongZ(), d);
						}
					}
					if (id == VoxelSnapshot.AIR) {
						// блок снизу может торчать сюда: ограда и забор в полтора
						// блока высотой, и эта половина раньше просто пропадала
						BlockState below = level.getBlockState(pos.set(wx, wy - 1, wz));
						if (!below.isAir() && !below.liquid() && below.blocksMotion()) {
							Shape shape = shapeOf(below, level, pos);
							if (shape.upVolume() > 0) {
								Materials material = materialOf(below.getSoundType());
								float d = material.density();
								id = (byte) material.ordinal();
								fill = scale(shape.upVolume(), d);
								cx = scale(shape.upX(), d);
								cy = scale(shape.upY(), d);
								cz = scale(shape.upZ(), d);
							}
						}
					}
					filling.set(lx, fillLayer, lz, id, fill, cx, cy, cz);
				}
			}
		}

		if (fillLayer >= size) {
			// снимки крутятся по кругу: пока решатель читает один, заполняем
			// следующий, а третий отдыхает — так куб не выделяется заново
			VoxelSnapshot next = spare;
			if (next == null || next.radius != radius) next = new VoxelSnapshot(radius);
			spare = ready;
			ready = filling;
			filling = next;
			fillLayer = 0;
			submitJob();
		}
	}

	/**
	 * Как блок закрывает клетку.
	 *
	 * Одной доли объёма для звука мало. Каменная ограда занимает от силы треть
	 * клетки, но поперёк неё не видно ничего: она сплошная во всю высоту, и
	 * горизонтальный звук она держит не хуже целого блока. Плита наоборот —
	 * половина объёма, но пройти вбок над ней можно свободно, а сверху вниз
	 * нельзя. Поэтому кроме объёма считаем, какую часть сечения блок закрывает,
	 * если смотреть вдоль каждой оси.
	 *
	 * Поля {@code up*} — то же самое для клетки выше: ограда и забор высотой в
	 * полтора блока торчат в неё, и без этого над ними оставалась щель, которой
	 * в мире нет.
	 */
	private record Shape(byte volume, byte alongX, byte alongY, byte alongZ,
	                     byte upVolume, byte upX, byte upY, byte upZ) {}

	private static final Shape FULL_SHAPE = new Shape((byte) 100, (byte) 100, (byte) 100, (byte) 100,
		(byte) 0, (byte) 0, (byte) 0, (byte) 0);
	/** Блок без столкновений — трава, цветы: звук их почти не замечает. */
	private static final Shape THIN_SHAPE = new Shape((byte) 20, (byte) 20, (byte) 20, (byte) 20,
		(byte) 0, (byte) 0, (byte) 0, (byte) 0);

	/** Разрешение разбора формы: 4×4×4 точки на клетку. */
	private static final int GRID = 4;

	private final Map<BlockState, Shape> shapeCache = new ConcurrentHashMap<>();

	private static byte scale(byte value, float density) {
		return (byte) Math.max(1, Math.min(100, Math.round(value * density)));
	}

	/**
	 * Разобрать форму блока по точкам.
	 *
	 * Считается один раз на состояние блока и запоминается: соединения оград и
	 * поворот ступеней — тоже часть состояния, так что кэш не врёт, а работы
	 * выходит на сотню-другую разных блоков вместо сотен тысяч клеток.
	 */
	private Shape shapeOf(BlockState state, Level level, BlockPos pos) {
		Shape cached = shapeCache.get(state);
		if (cached != null) return cached;
		Shape computed = measure(state, level, pos);
		shapeCache.put(state, computed);
		return computed;
	}

	private static Shape measure(BlockState state, Level level, BlockPos pos) {
		try {
			if (state.isCollisionShapeFullBlock(level, pos)) return FULL_SHAPE;
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (shape.isEmpty()) return THIN_SHAPE;
			List<AABB> boxes = shape.toAabbs();
			if (boxes.isEmpty()) return THIN_SHAPE;

			boolean[] here = new boolean[GRID * GRID * GRID];
			boolean[] above = new boolean[GRID * GRID * GRID];
			for (int iy = 0; iy < GRID; iy++) {
				double y = (iy + 0.5) / GRID;
				for (int iz = 0; iz < GRID; iz++) {
					double z = (iz + 0.5) / GRID;
					for (int ix = 0; ix < GRID; ix++) {
						double x = (ix + 0.5) / GRID;
						int at = (iy * GRID + iz) * GRID + ix;
						here[at] = inside(boxes, x, y, z);
						above[at] = inside(boxes, x, y + 1, z);
					}
				}
			}
			return new Shape(
				volumeOf(here), coverOf(here, 0), coverOf(here, 1), coverOf(here, 2),
				volumeOf(above), coverOf(above, 0), coverOf(above, 1), coverOf(above, 2));
		} catch (Throwable ignored) {
			// мод может отдать что угодно — считаем блок целым, так безопаснее
			return FULL_SHAPE;
		}
	}

	private static boolean inside(List<AABB> boxes, double x, double y, double z) {
		for (int i = 0; i < boxes.size(); i++) {
			AABB box = boxes.get(i);
			if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY
				&& z >= box.minZ && z <= box.maxZ) return true;
		}
		return false;
	}

	private static byte volumeOf(boolean[] grid) {
		int count = 0;
		for (boolean b : grid) if (b) count++;
		return (byte) Math.round(count * 100f / grid.length);
	}

	/**
	 * Какую часть сечения закрывает форма, если смотреть вдоль оси.
	 *
	 * Столбик считается закрытым, если на нём есть хоть одна занятая точка:
	 * звук вдоль этой линии в неё упрётся.
	 */
	private static byte coverOf(boolean[] grid, int axis) {
		int blocked = 0;
		for (int a = 0; a < GRID; a++) {
			for (int b = 0; b < GRID; b++) {
				boolean any = false;
				for (int t = 0; t < GRID && !any; t++) {
					int ix, iy, iz;
					if (axis == 0) { ix = t; iy = a; iz = b; }
					else if (axis == 1) { ix = a; iy = t; iz = b; }
					else { ix = a; iy = b; iz = t; }
					any = grid[(iy * GRID + iz) * GRID + ix];
				}
				if (any) blocked++;
			}
		}
		return (byte) Math.round(blocked * 100f / (GRID * GRID));
	}

	private void submitJob() {
		VoxelSnapshot snapshot = ready;
		if (snapshot == null) return;
		long now = System.currentTimeMillis();
		if (now - lastSubmit < budget.intervalMs()) return;
		lastSubmit = now;

		List<Source> list = new ArrayList<>(playing.values());
		list.removeIf(s -> s.finished || s.relative || !snapshot.covers(s.x, s.y, s.z));
		int limit = Math.min(Tracer.MAX_SOURCES, budget.sources());
		if (list.size() > limit) {
			// считать честно всё сразу дорого, поэтому лишнее отбрасываем — но
			// не первое попавшееся, а самое далёкое: близкий звук важнее
			double lx = mixer.listenerX, ly = mixer.listenerY, lz = mixer.listenerZ;
			list.sort(java.util.Comparator.comparingDouble(s -> {
				double dx = s.x - lx, dy = s.y - ly, dz = s.z - lz;
				return dx * dx + dy * dy + dz * dz;
			}));
			list = new ArrayList<>(list.subList(0, limit));
		}
		final List<Source> sources = list;
		final float speed = mixer.air.speedOfSound;

		solver.submit(new Solver.Job() {
			public VoxelSnapshot snapshot() { return snapshot; }
			public int sourceCount() { return sources.size(); }
			public long sourceId(int i) { return sources.get(i).id; }
			public double sourceX(int i) { return sources.get(i).x; }
			public double sourceY(int i) { return sources.get(i).y; }
			public double sourceZ(int i) { return sources.get(i).z; }
			public boolean sourceImpact(int i) { return sources.get(i).impact; }
			public float speedOfSound() { return speed; }
		});
	}

	/* --- перенос решений в источники --- */

	private void applySolutions(Level level) {
		for (Map.Entry<SoundInstance, Source> entry : playing.entrySet()) {
			SoundInstance instance = entry.getKey();
			Source source = entry.getValue();
			if (source.finished) { playing.remove(instance); solver.forget(source.id); continue; }

			// звук, который сам себя остановил (мобы, вагонетки, маяк), игра нам
			// уже не отдаст — снимаем его здесь
			if (instance instanceof net.minecraft.client.resources.sounds.TickableSoundInstance tickable
				&& tickable.isStopped()) {
				stop(instance);
				continue;
			}

			// музыка и эмбиент: положения в мире у них нет, но среда на них влияет
			if (source.relative) {
				if (soundEngine != null) {
					SoundEngineAccessor accessor = (SoundEngineAccessor) soundEngine;
					float live = accessor.d3sound$calculateVolume(instance);
					if (live > 0) source.volume = live; else source.stopping = true;
					source.pitch = accessor.d3sound$calculatePitch(instance);
				}
				applyLocal(source);
				continue;
			}

			source.x = instance.getX();
			source.y = instance.getY();
			source.z = instance.getZ();
			// громкость и высота живут своей жизнью: ползунки игры, затухание
			// музыки, разгон вагонетки — всё это меняется уже после запуска
			if (soundEngine != null) {
				SoundEngineAccessor accessor = (SoundEngineAccessor) soundEngine;
				float live = accessor.d3sound$calculateVolume(instance);
				if (live > 0) source.volume = live; else source.stopping = true;
				source.pitch = accessor.d3sound$calculatePitch(instance);
			}

			// звук, пересекающий поверхность воды, теряет почти всё, кроме низа
			boolean sourceUnderwater = level.getFluidState(
				BlockPos.containing(source.x, source.y, source.z)).is(FluidTags.WATER);
			boolean crossesSurface = sourceUnderwater != listenerUnderwater;

			Solution solution = solver.solutionFor(source.id);
			if (solution == null || solution.tapCount == 0) { applyFallback(source); continue; }

			int count = Math.min(solution.tapCount, Source.MAX_TAPS);
			for (int t = 0; t < count; t++) {
				float distance = solution.delay[t] * mixer.air.speedOfSound;
				float dx = solution.dir[t][0], dy = solution.dir[t][1], dz = solution.dir[t][2];

				// прямой путь пересчитываем по текущим позициям: от этого доплер и точность
				if (t == 0 && !solution.directBlocked) {
					double wx = source.x - mixer.listenerX;
					double wy = source.y - mixer.listenerY;
					double wz = source.z - mixer.listenerZ;
					distance = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
					dx = (float) wx; dy = (float) wy; dz = (float) wz;
					float spread = Solver.spread(distance);
					for (int b = 0; b < Materials.BAND_COUNT; b++) bandBuffer[b] = spread;
				} else {
					System.arraycopy(solution.bands[t], 0, bandBuffer, 0, Materials.BAND_COUNT);
				}

				if (crossesSurface) {
					for (int b = 0; b < Materials.BAND_COUNT; b++) {
						bandBuffer[b] *= (float) Math.pow(10.0, -Air.SURFACE_LOSS_DB[b] / 20.0);
					}
				}

				Binaural.toListenerFrame(dx, dy, dz, mixer.listenerYaw, mixer.listenerPitch, dirBuffer);
				binaural.compute(dirBuffer, distance, mixer.air, bandBuffer, ears);

				Source.Tap tap = source.taps[t];
				if (!tap.active) tap.arm();
				tap.targetDelayLeft = ears.delayLeft;
				tap.targetDelayRight = ears.delayRight;
				System.arraycopy(ears.gainLeft, 0, tap.targetGainLeft, 0, 3);
				System.arraycopy(ears.gainRight, 0, tap.targetGainRight, 0, 3);
				tap.active = true;
			}
			for (int t = count; t < Source.MAX_TAPS; t++) source.taps[t].active = false;
			source.tapCount = count;
			source.targetSend = solution.tailLevel;
		}
	}

	/** Насколько сильно среда красит местные звуки. 0 — отдать их игре как есть. */
	private volatile float localAmount = 1f;

	/**
	 * Под водой у местных звуков глохнет верх, дБ по полосам.
	 *
	 * Голова в воде: до барабанной перепонки высокие частоты почти не доходят,
	 * остаётся низ и ощущение давления. Ровно поэтому под водой музыка звучит
	 * так, будто играет за стеной.
	 */
	private static final float[] SUBMERGED_DB = {1f, 2f, 4f, 7f, 12f, 18f, 24f};
	/** То же в лаве, только сильнее: среда вязкая и горячая. */
	private static final float[] MOLTEN_DB = {2f, 4f, 8f, 13f, 20f, 28f, 36f};

	/**
	 * Местный звук: музыка, эмбиент, всё, что звучит «в голове».
	 *
	 * Направления и расстояния у него нет — придумывать их нечестно. Зато на
	 * него влияет то, где находится сам игрок: под водой глохнет верх, в лаве
	 * ещё сильнее, а в замкнутом помещении к музыке добавляется хвост той же
	 * длины, что и у всего остального вокруг. В чистом поле хвоста нет — там
	 * ему неоткуда взяться.
	 */
	private void applyLocal(Source source) {
		float amount = localAmount;
		float[] colour = listenerInLava ? MOLTEN_DB : (listenerUnderwater ? SUBMERGED_DB : null);
		for (int b = 0; b < Materials.BAND_COUNT; b++) {
			bandBuffer[b] = colour == null ? 1f
				: (float) Math.pow(10.0, -(colour[b] * amount) / 20.0);
		}

		Source.Tap tap = source.taps[0];
		if (!tap.active) tap.arm();
		tap.targetDelayLeft = 0f;
		tap.targetDelayRight = 0f;
		// три полосы микшера: низ, середина, верх — усредняем в них спектр
		tap.targetGainLeft[0] = tap.targetGainRight[0] = (bandBuffer[0] + bandBuffer[1]) / 2;
		tap.targetGainLeft[1] = tap.targetGainRight[1] = (bandBuffer[2] + bandBuffer[3] + bandBuffer[4]) / 3;
		tap.targetGainLeft[2] = tap.targetGainRight[2] = (bandBuffer[5] + bandBuffer[6]) / 2;
		tap.active = true;
		for (int t = 1; t < Source.MAX_TAPS; t++) source.taps[t].active = false;
		source.tapCount = 1;

		// хвост помещения: в пещере музыка гулкая, в поле сухая
		float[] rt = solver.rt60;
		float mid = rt.length > 2 ? rt[2] : 0f;
		float room = Math.min(0.6f, mid * 0.25f) * (1f - solver.openness);
		float wet = listenerUnderwater || listenerInLava ? Math.max(room, 0.25f) : room;
		source.targetSend = wet * amount;
	}

	/** Пока решателя нет: прямой звук без преград. */
	private void applyFallback(Source source) {
		// у местного звука позиции нет: считать по ней расстояние — значит
		// на первом же кадре увести музыку в тишину
		if (source.relative) { applyLocal(source); return; }

		double dx = source.x - mixer.listenerX;
		double dy = source.y - mixer.listenerY;
		double dz = source.z - mixer.listenerZ;
		float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float spread = Solver.spread(distance);
		for (int b = 0; b < Materials.BAND_COUNT; b++) bandBuffer[b] = spread;

		Binaural.toListenerFrame(dx, dy, dz, mixer.listenerYaw, mixer.listenerPitch, dirBuffer);
		binaural.compute(dirBuffer, distance, mixer.air, bandBuffer, ears);

		Source.Tap tap = source.taps[0];
		if (!tap.active) tap.arm();
		tap.targetDelayLeft = ears.delayLeft;
		tap.targetDelayRight = ears.delayRight;
		System.arraycopy(ears.gainLeft, 0, tap.targetGainLeft, 0, 3);
		System.arraycopy(ears.gainRight, 0, tap.targetGainRight, 0, 3);
		tap.active = true;
		for (int t = 1; t < Source.MAX_TAPS; t++) source.taps[t].active = false;
		source.tapCount = 1;
		// пока решения нет, про помещение мы ничего не знаем — в хвост почти ничего
		source.targetSend = 0.15f * Solver.spread(distance) * (1f - solver.openness);
	}

	/** Тип звука блока → акустический материал. */
	public static Materials materialOf(SoundType type) {
		if (type == SoundType.WOOD || type == SoundType.BAMBOO || type == SoundType.BAMBOO_SAPLING
			|| type == SoundType.LADDER || type == SoundType.SCAFFOLDING || type == SoundType.STEM) return Materials.WOOD;
		if (type == SoundType.WOOL || type == SoundType.CANDLE) return Materials.WOOL;
		if (type == SoundType.GLASS || type == SoundType.AMETHYST) return Materials.GLASS;
		if (type == SoundType.METAL || type == SoundType.ANVIL || type == SoundType.CHAIN
			|| type == SoundType.NETHERITE_BLOCK || type == SoundType.LODESTONE) return Materials.METAL;
		if (type == SoundType.SAND || type == SoundType.GRAVEL || type == SoundType.SOUL_SAND) return Materials.SAND;
		if (type == SoundType.SNOW || type == SoundType.POWDER_SNOW) return Materials.SNOW;
		if (type == SoundType.GRASS || type == SoundType.NYLIUM || type == SoundType.ROOTS
			|| type == SoundType.SOUL_SOIL) return Materials.DIRT;
		if (type == SoundType.VINE || type == SoundType.CROP || type == SoundType.HARD_CROP
			|| type == SoundType.SWEET_BERRY_BUSH || type == SoundType.NETHER_SPROUTS
			|| type == SoundType.WEEPING_VINES || type == SoundType.TWISTING_VINES) return Materials.FOLIAGE;
		if (type == SoundType.WET_GRASS || type == SoundType.LILY_PAD) return Materials.WATER;
		if (type == SoundType.SLIME_BLOCK || type == SoundType.HONEY_BLOCK) return Materials.SOFT;
		if (type == SoundType.NETHER_BRICKS || type == SoundType.NETHERRACK) return Materials.BRICK;
		return Materials.STONE;
	}

	/* ------------------------------------------------------------------ */
	/*  вывод                                                              */
	/* ------------------------------------------------------------------ */

	private void ensureOutput() {
		if (soundEngine == null) return;
		if (output != null && !output.isStopped()) return;
		if (openingOutput) return;
		SoundEngineAccessor accessor = (SoundEngineAccessor) soundEngine;
		if (!accessor.d3sound$loaded()) return;
		openingOutput = true;

		D3Stream stream = new D3Stream(mixer);
		accessor.d3sound$channelAccess()
			.createHandle(com.mojang.blaze3d.audio.Library.Pool.STREAMING)
			.thenAccept(handle -> {
				openingOutput = false;
				if (handle == null) return;
				output = handle;
				handle.execute(channel -> {
					channel.setRelative(true);
					channel.disableAttenuation();
					channel.setVolume(1f);
					channel.setPitch(1f);
					channel.setSelfPosition(net.minecraft.world.phys.Vec3.ZERO);
					channel.attachBufferStream(stream);
					channel.play();
				});
				startPump();
				LOG.info("D3Sound: канал вывода поднят");
			}).exceptionally(error -> {
				openingOutput = false;
				LOG.warn("D3Sound: канал вывода не поднялся: {}", error.toString());
				return null;
			});
	}

	private void startPump() {
		if (pump != null) return;
		pump = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "D3Sound-pump");
			t.setDaemon(true);
			return t;
		});
		pump.scheduleAtFixedRate(() -> {
			ChannelAccess.ChannelHandle handle = output;
			if (handle != null && !handle.isStopped()) handle.execute(Channel::updateStream);
		}, 20, 15, TimeUnit.MILLISECONDS);
	}

	public void shutdown() {
		stopAll();
		solver.stop();
		if (pump != null) { pump.shutdownNow(); pump = null; }
		if (streamPump != null) { streamPump.shutdownNow(); streamPump = null; }
		output = null;
		pcmCache.clear();
	}

	/** Строка для отладочной клавиши. */
	public String status() {
		float[] rt = solver.rt60;
		return String.format("D3Sound: источников %d · RT60 %.2f с · пробег %.1f м · %s",
			playing.size(), rt.length > 2 ? rt[2] : 0f, solver.meanFreePath, budget.describe());
	}
}
