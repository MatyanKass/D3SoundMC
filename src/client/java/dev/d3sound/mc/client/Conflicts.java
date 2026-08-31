package dev.d3sound.mc.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Моды, которые спорят с нами за звук.
 *
 * Мы забираем звуки у движка игры и считаем их сами, поэтому всё, что тоже
 * лезет в звук, либо перестаёт работать, либо мешает: два движка на один
 * источник — это либо двойной звук, либо тишина.
 *
 * Список нужен не чтобы что-то запрещать, а чтобы было видно, почему звучит
 * не так, как ожидалось.
 */
public final class Conflicts {
	/** Насколько серьёзно мод мешает. */
	public enum Level { BLOCKING, PARTIAL, MINOR }

	public record Entry(String modId, Level level, String reason) {}

	private static final List<Entry> KNOWN = List.of(
		new Entry("soundphysics", Level.BLOCKING,
			"Тоже переписывает звук целиком: отражения, перекрытия и реверберацию. Работать будут оба сразу — звук задваивается и глушится дважды."),
		new Entry("extremesoundmuffler", Level.BLOCKING,
			"Перехватывает и приглушает звуки до нас. Его фильтры к нашим путям не применяются, зато громкость он режет."),
		new Entry("dynamicsurroundings", Level.BLOCKING,
			"Свой звуковой слой с эхом и окружением — накладывается поверх нашего."),
		new Entry("presencefootsteps", Level.PARTIAL,
			"Подменяет звуки шагов своими. Они пройдут через нас, но материал под ногами он определяет по-своему."),
		new Entry("ambientsounds", Level.PARTIAL,
			"Играет окружение собственным потоком мимо нашего движка: у этих звуков не будет ни отражений, ни перекрытий."),
		new Entry("ambientenvironment", Level.PARTIAL,
			"То же самое для погодных и биомных звуков."),
		new Entry("sounds", Level.PARTIAL,
			"Меняет и добавляет звуки интерфейса и мира; часть из них идёт мимо нашей обработки."),
		new Entry("audioplayer", Level.MINOR,
			"Проигрывает пользовательские записи потоком — потоковые звуки мы не перехватываем."),
		new Entry("voicechat", Level.MINOR,
			"Голосовой чат идёт своим путём. Пространственную обработку он делает сам, наша к нему пока не применяется.")
	);

	private Conflicts() {}

	/** Что из известного действительно стоит у игрока. */
	public static List<Found> detect() {
		FabricLoader loader = FabricLoader.getInstance();
		List<Found> found = new ArrayList<>();
		for (Entry entry : KNOWN) {
			ModContainer container = loader.getModContainer(entry.modId()).orElse(null);
			if (container == null) continue;
			found.add(new Found(entry, container.getMetadata().getName()));
		}
		found.sort((a, b) -> a.entry().level().compareTo(b.entry().level()));
		return found;
	}

	public record Found(Entry entry, String name) {}
}
