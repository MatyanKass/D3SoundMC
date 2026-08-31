package dev.d3sound.mc.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Кто ещё в этой сборке лезет в звук.
 *
 * Список не зашит заранее — у каждого игрока свои моды. Вместо этого мы
 * смотрим, что мод на самом деле трогает: чтобы вмешаться в звук игры, надо
 * обратиться к её звуковым классам, а имена этих классов остаются в
 * скомпилированном коде. Находим их — значит, мод в звук лезет, и понятно,
 * куда именно.
 *
 * Разбираются только классы примесей и всё, что названо звуком: этого хватает,
 * чтобы поймать любой звуковой мод, и это не стоит заметного времени.
 */
public final class Conflicts {
	/** Насколько серьёзно мод мешает. */
	public enum Level { BLOCKING, PARTIAL, MINOR }

	/** Найденное вмешательство: мод, куда лезет и чем это грозит. */
	public record Found(String modId, String name, Level level, String reason) {}

	/** Что ищем в коде мода и как это назвать по-человечески. */
	private record Marker(String className, Level level, String what) {}

	private static final List<Marker> MARKERS = List.of(
		new Marker("net/minecraft/client/sounds/SoundEngine", Level.BLOCKING, "движок звука"),
		new Marker("net/minecraft/client/sounds/ChannelAccess", Level.BLOCKING, "звуковые каналы"),
		new Marker("net/minecraft/client/sounds/SoundBufferLibrary", Level.BLOCKING, "буферы звука"),
		new Marker("com/mojang/blaze3d/audio/Channel", Level.BLOCKING, "канал OpenAL"),
		new Marker("com/mojang/blaze3d/audio/Library", Level.BLOCKING, "библиотеку OpenAL"),
		new Marker("com/mojang/blaze3d/audio/Listener", Level.BLOCKING, "слушателя OpenAL"),
		new Marker("net/minecraft/client/sounds/SoundManager", Level.PARTIAL, "выдачу звуков"),
		new Marker("net/minecraft/client/sounds/AudioStream", Level.PARTIAL, "звуковые потоки"),
		new Marker("net/minecraft/client/resources/sounds/SoundInstance", Level.PARTIAL, "сами звуки")
	);

	/** Свои и служебные — их проверять незачем. */
	private static final Set<String> SKIP = Set.of("d3sound", "minecraft", "java", "fabricloader", "fabric-api", "mixinextras");

	private static volatile List<Found> result;
	private static volatile boolean scanning;

	private Conflicts() {}

	/** Уже посчитанный список; {@code null}, пока проверка не закончилась. */
	public static List<Found> result() { return result; }

	public static boolean scanning() { return scanning; }

	/** Запустить проверку в фоне — она читает файлы, на игровом потоке ей делать нечего. */
	public static void scanInBackground() {
		if (scanning || result != null) return;
		scanning = true;
		Thread thread = new Thread(() -> {
			try {
				List<Found> list = scan();
				result = list;
				for (Found f : list) {
					D3SoundEngine.LOG.info("D3Sound: {} ({}) — {}", f.name(), f.level(), f.reason());
				}
				D3SoundEngine.LOG.info("D3Sound: проверка модов закончена, задето звука у {} из {}",
					list.size(), FabricLoader.getInstance().getAllMods().size());
			} catch (Throwable error) {
				D3SoundEngine.LOG.warn("D3Sound: проверка модов не удалась: {}", error.toString());
				result = List.of();
			} finally {
				scanning = false;
			}
		}, "D3Sound-conflicts");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	/** Пересчитать заново (например, после смены сборки модов). */
	public static void refresh() {
		result = null;
		scanInBackground();
	}

	private static List<Found> scan() {
		List<Found> found = new ArrayList<>();
		for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
			String id = container.getMetadata().getId();
			if (SKIP.contains(id) || id.startsWith("fabric-")) continue;
			Level level = null;
			Set<String> touched = new LinkedHashSet<>();

			for (Path root : container.getRootPaths()) {
				try (Stream<Path> files = Files.walk(root, 12)) {
					for (Path file : (Iterable<Path>) files.filter(Conflicts::interesting)::iterator) {
						byte[] bytes;
						try {
							if (Files.size(file) > 512 * 1024) continue;
							bytes = Files.readAllBytes(file);
						} catch (IOException ignored) {
							continue;
						}
						String text = new String(bytes, StandardCharsets.ISO_8859_1);
						for (Marker marker : MARKERS) {
							if (!text.contains(marker.className())) continue;
							touched.add(marker.what());
							if (level == null || marker.level().compareTo(level) < 0) level = marker.level();
						}
					}
				} catch (IOException | RuntimeException ignored) {
					// мод может лежать как угодно — не смогли прочитать, и ладно
				}
			}

			if (level == null) continue;
			found.add(new Found(id, container.getMetadata().getName(), level, describe(level, touched)));
		}
		found.sort(Comparator.comparing((Found f) -> f.level().ordinal()).thenComparing(Found::name));
		return List.copyOf(found);
	}

	/** Стоит ли вообще открывать файл. */
	private static boolean interesting(Path path) {
		String name = path.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
		if (!name.endsWith(".class")) return false;
		return name.contains("mixin") || name.contains("sound") || name.contains("audio");
	}

	private static String describe(Level level, Set<String> touched) {
		String parts = String.join(", ", touched);
		return switch (level) {
			case BLOCKING -> "Вмешивается в " + parts + ". Мы забираем звуки себе, поэтому вместе получится "
				+ "либо двойная обработка, либо тишина — такой мод лучше отключить.";
			case PARTIAL -> "Трогает " + parts + ". Работать будет, но его звуки могут идти мимо нашей физики.";
			case MINOR -> "Слегка касается звука (" + parts + "), заметных помех быть не должно.";
		};
	}
}
