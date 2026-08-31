package dev.d3sound.mc.client;

import com.mojang.blaze3d.audio.Channel;
import dev.d3sound.mc.audio.Air;
import dev.d3sound.mc.audio.Binaural;
import dev.d3sound.mc.audio.Materials;
import dev.d3sound.mc.audio.Mixer;
import dev.d3sound.mc.audio.Source;
import dev.d3sound.mc.audio.VoxelAcoustics;
import dev.d3sound.mc.client.mixin.SoundBufferAccessor;
import dev.d3sound.mc.client.mixin.SoundEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Связка нашего движка с игрой.
 *
 * Звук перехватывается на входе в звуковую систему, распаковывается в семплы и
 * дальше живёт у нас: положение, перекрытие блоками и свойства помещения
 * считаются на игровом потоке, а сведение идёт в аудиопотоке через один общий
 * канал.
 */
public final class D3SoundEngine {
	public static final Logger LOG = LoggerFactory.getLogger("D3Sound");
	private static final D3SoundEngine INSTANCE = new D3SoundEngine();

	public static D3SoundEngine get() { return INSTANCE; }

	private D3SoundEngine() {}

	/** Наш движок включён. Выключение мгновенно возвращает ванильный звук. */
	public volatile boolean enabled = true;
	/** Отладочная информация в лог. */
	public volatile boolean verbose = false;

	private final Mixer mixer = new Mixer();
	private final Binaural binaural = new Binaural();
	private final Binaural.Ears ears = new Binaural.Ears();
	private final float[] dir = new float[3];
	private final float[] occlusion = new float[Materials.BAND_COUNT];

	private final Map<SoundInstance, Source> playing = new ConcurrentHashMap<>();
	private final Map<Identifier, Pcm> pcmCache = new ConcurrentHashMap<>();

	private SoundEngine soundEngine;
	private ChannelAccess.ChannelHandle output;
	private ScheduledExecutorService pump;
	private int tickCounter;
	private VoxelAcoustics.Probe lastProbe;

	private record Pcm(float[] samples, int sampleRate) {}

	public Mixer mixer() { return mixer; }

	public VoxelAcoustics.Probe lastProbe() { return lastProbe; }

	public int activeSources() { return playing.size(); }

	/* ------------------------------------------------------------------ */
	/*  перехват звука                                                     */
	/* ------------------------------------------------------------------ */

	/**
	 * Забрать звук себе. Возвращает false, если звук лучше оставить игре
	 * (музыка, пластинки, интерфейс — их пространственная обработка не нужна).
	 */
	public boolean takeOver(SoundEngine engine, SoundInstance instance,
	                        net.minecraft.client.resources.sounds.Sound sound,
	                        float volume, float pitch, boolean looping) {
		if (!enabled) return false;
		if (sound.shouldStream()) return false;                 // музыка и пластинки
		if (instance.isRelative()) return false;                // интерфейс
		if (instance.getAttenuation() == SoundInstance.Attenuation.NONE) return false;

		this.soundEngine = engine;
		Identifier path = sound.getPath();

		Pcm cached = pcmCache.get(path);
		if (cached != null) {
			start(instance, sound, cached, volume, pitch, looping);
			return true;
		}

		SoundEngineAccessor accessor = (SoundEngineAccessor) engine;
		accessor.d3sound$soundBuffers().getCompleteBuffer(path).thenAccept(buffer -> {
			Pcm pcm = decode(buffer);
			if (pcm == null) return;
			pcmCache.put(path, pcm);
			start(instance, sound, pcm, volume, pitch, looping);
		}).exceptionally(error -> {
			LOG.warn("D3Sound: не удалось получить семплы {}: {}", path, error.toString());
			return null;
		});
		return true;
	}

	private void start(SoundInstance instance, net.minecraft.client.resources.sounds.Sound sound,
	                   Pcm pcm, float volume, float pitch, boolean looping) {
		Source source = new Source(instance.getIdentifier().toString(), pcm.samples(), pcm.sampleRate(),
			looping, instance.isRelative());
		source.x = instance.getX();
		source.y = instance.getY();
		source.z = instance.getZ();
		source.volume = volume;
		source.pitch = pitch;
		updateSource(source, instance);
		playing.put(instance, source);
		mixer.add(source);
		if (verbose) {
			LOG.info("D3Sound: перехвачен {} в ({}, {}, {}), громкость {}",
				source.name, (int) source.x, (int) source.y, (int) source.z, volume);
		}
	}

	public void stop(SoundInstance instance) {
		Source source = playing.remove(instance);
		if (source != null) source.stopping = true;
	}

	public void stopAll() {
		for (Source s : playing.values()) s.stopping = true;
		playing.clear();
	}

	/** Распаковка семплов игры в моно-массив float. */
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
		LOG.warn("D3Sound: неизвестный формат семплов: {} бит", bits);
		return null;
	}

	/* ------------------------------------------------------------------ */
	/*  физика: обновление на игровом потоке                               */
	/* ------------------------------------------------------------------ */

	public void updateListener(double x, double y, double z, float yawDeg, float pitchDeg) {
		mixer.listenerX = x;
		mixer.listenerY = y;
		mixer.listenerZ = z;
		mixer.listenerYaw = yawDeg;
		mixer.listenerPitch = pitchDeg;
	}

	/** Вызывается каждый клиентский тик. */
	public void clientTick(Minecraft client) {
		if (!enabled) return;
		ensureOutput();

		Level level = client.level;
		if (level == null) return;

		if (client.player != null) {
			updateListener(client.player.getX(), client.player.getEyeY(), client.player.getZ(),
				client.player.getYRot(), client.player.getXRot());
		}

		// среда: погода и биом
		if (++tickCounter % 40 == 0) {
			float biomeTemp = level.getBiome(BlockPos.containing(mixer.listenerX, mixer.listenerY, mixer.listenerZ))
				.value().getBaseTemperature();
			mixer.air = Air.forWeather(level.isRaining(), level.isThundering() && biomeTemp < 0.15f, biomeTemp);
		}

		// свойства помещения — реже, они меняются медленно
		if (tickCounter % 10 == 0) {
			VoxelAcoustics.BlockSampler sampler = sampler(level);
			lastProbe = VoxelAcoustics.probe(sampler, mixer.listenerX, mixer.listenerY, mixer.listenerZ,
				mixer.air.speedOfSound);
			mixer.applyRoom(lastProbe);
		}

		VoxelAcoustics.BlockSampler sampler = sampler(level);
		for (Map.Entry<SoundInstance, Source> entry : playing.entrySet()) {
			SoundInstance instance = entry.getKey();
			Source source = entry.getValue();
			if (source.finished) { playing.remove(instance); continue; }
			source.x = instance.getX();
			source.y = instance.getY();
			source.z = instance.getZ();
			updateSourcePhysics(source, sampler);
		}
	}

	private void updateSource(Source source, SoundInstance instance) {
		source.x = instance.getX();
		source.y = instance.getY();
		source.z = instance.getZ();
	}

	private void updateSourcePhysics(Source source, VoxelAcoustics.BlockSampler sampler) {
		double dx = source.x - mixer.listenerX;
		double dy = source.y - mixer.listenerY;
		double dz = source.z - mixer.listenerZ;
		float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

		VoxelAcoustics.occlusion(sampler, source.x, source.y, source.z,
			mixer.listenerX, mixer.listenerY, mixer.listenerZ, occlusion);

		Binaural.toListenerFrame(dx, dy, dz, mixer.listenerYaw, mixer.listenerPitch, dir);
		binaural.compute(dir, distance, mixer.air, occlusion, source.volume, 0.5f, ears);

		source.targetDelayLeft = ears.delayLeft;
		source.targetDelayRight = ears.delayRight;
		System.arraycopy(ears.gainLeft, 0, source.targetGainLeft, 0, 3);
		System.arraycopy(ears.gainRight, 0, source.targetGainRight, 0, 3);

		// в поле уходит энергия источника, а не прямой путь: в помещении
		// отражения приходят даже когда прямой путь перекрыт
		float meanOcclusion = 0;
		for (float g : occlusion) meanOcclusion += g;
		meanOcclusion /= occlusion.length;
		float openness = lastProbe != null ? lastProbe.openness : 1f;
		source.targetSend = source.volume * 0.45f * (1 - 0.85f * openness)
			* (0.35f + 0.65f * meanOcclusion) / Math.max(1f, distance * 0.15f);
	}

	/** Блоки мира как акустическая сетка. */
	private static VoxelAcoustics.BlockSampler sampler(BlockGetter level) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return (x, y, z) -> {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.isAir()) return null;
			if (state.liquid()) return Materials.WATER;
			if (!state.blocksMotion()) return null;
			return materialOf(state.getSoundType());
		};
	}

	/** Тип звука блока — в акустический материал. */
	public static Materials materialOf(SoundType type) {
		if (type == SoundType.WOOD || type == SoundType.BAMBOO || type == SoundType.BAMBOO_SAPLING
			|| type == SoundType.LADDER || type == SoundType.SCAFFOLDING || type == SoundType.STEM) {
			return Materials.WOOD;
		}
		if (type == SoundType.WOOL || type == SoundType.CANDLE) return Materials.WOOL;
		if (type == SoundType.GLASS || type == SoundType.AMETHYST) return Materials.GLASS;
		if (type == SoundType.METAL || type == SoundType.ANVIL || type == SoundType.CHAIN
			|| type == SoundType.NETHERITE_BLOCK || type == SoundType.LODESTONE) {
			return Materials.METAL;
		}
		if (type == SoundType.SAND || type == SoundType.GRAVEL || type == SoundType.SOUL_SAND) return Materials.SAND;
		if (type == SoundType.SNOW || type == SoundType.POWDER_SNOW) return Materials.SNOW;
		if (type == SoundType.GRASS || type == SoundType.NYLIUM || type == SoundType.ROOTS
			|| type == SoundType.SOUL_SOIL) {
			return Materials.DIRT;
		}
		if (type == SoundType.VINE || type == SoundType.CROP || type == SoundType.HARD_CROP
			|| type == SoundType.SWEET_BERRY_BUSH || type == SoundType.NETHER_SPROUTS
			|| type == SoundType.WEEPING_VINES || type == SoundType.TWISTING_VINES) {
			return Materials.FOLIAGE;
		}
		if (type == SoundType.WET_GRASS || type == SoundType.LILY_PAD) return Materials.WATER;
		if (type == SoundType.SLIME_BLOCK || type == SoundType.HONEY_BLOCK) return Materials.SOFT;
		if (type == SoundType.NETHER_BRICKS || type == SoundType.NETHERRACK) return Materials.BRICK;
		return Materials.STONE;
	}

	/* ------------------------------------------------------------------ */
	/*  вывод                                                              */
	/* ------------------------------------------------------------------ */

	/** Держим собственный канал живым: через него уходит весь наш микс. */
	private void ensureOutput() {
		if (soundEngine == null) return;
		if (output != null && !output.isStopped()) return;

		SoundEngineAccessor accessor = (SoundEngineAccessor) soundEngine;
		if (!accessor.d3sound$loaded()) return;

		D3Stream stream = new D3Stream(mixer);
		accessor.d3sound$channelAccess()
			.createHandle(com.mojang.blaze3d.audio.Library.Pool.STREAMING)
			.thenAccept(handle -> {
				if (handle == null) {
					LOG.warn("D3Sound: не удалось получить канал вывода");
					return;
				}
				output = handle;
				handle.execute(channel -> {
					channel.setRelative(true);          // микс уже бинауральный
					channel.disableAttenuation();
					channel.setVolume(1f);
					channel.setPitch(1f);
					channel.setSelfPosition(net.minecraft.world.phys.Vec3.ZERO);
					channel.attachBufferStream(stream);
					channel.play();
				});
				startPump();
				LOG.info("D3Sound: канал вывода поднят");
			});
	}

	/**
	 * Игра подкачивает потоки раз в тик — для интерактивного звука это слишком
	 * редко, поэтому качаем свой канал чаще.
	 */
	private void startPump() {
		if (pump != null) return;
		pump = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "D3Sound-pump");
			t.setDaemon(true);
			return t;
		});
		pump.scheduleAtFixedRate(() -> {
			ChannelAccess.ChannelHandle handle = output;
			if (handle != null && !handle.isStopped()) {
				handle.execute(Channel::updateStream);
			}
		}, 20, 15, TimeUnit.MILLISECONDS);
	}

	public void shutdown() {
		stopAll();
		if (pump != null) {
			pump.shutdownNow();
			pump = null;
		}
		output = null;
		pcmCache.clear();
	}
}
