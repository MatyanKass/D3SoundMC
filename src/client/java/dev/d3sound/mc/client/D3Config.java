package dev.d3sound.mc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки движка.
 *
 * Живут рядом с остальными конфигами игры и правятся из экрана настроек звука.
 * Ноль в числовом поле означает «Авто»: значение подбирает сам движок, исходя
 * из того, сколько свободен процессор и что за помещение вокруг.
 */
public final class D3Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static D3Config instance;

	/** Свой движок вместо ванильного. */
	public boolean enabled = true;
	/** Качество расчёта, %. 0 — Авто по загрузке процессора. */
	public int quality = 0;
	/** До какой загрузки процессора разрешено подниматься, %. */
	public int cpuHeadroom = 70;
	/** Огибание преград. */
	public boolean diffraction = true;
	/** Отражения от геометрии мира. */
	public boolean reflections = true;
	/** Уровень хвоста реверберации, %. 0 — Авто по времени затухания помещения. */
	public int reverb = 0;
	/** Сила эффекта Доплера, %. 100 — как в жизни. */
	public int doppler = 100;
	/** Общая громкость движка, %. Поверх ползунков самой игры. */
	public int gain = 150;
	/** Показывать счётчик нагрузки на экране. */
	public boolean overlay = false;

	public static D3Config get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("d3sound.json");
	}

	private static D3Config load() {
		Path path = file();
		try {
			if (Files.exists(path)) {
				D3Config loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), D3Config.class);
				if (loaded != null) return loaded;
			}
		} catch (Exception error) {
			D3SoundEngine.LOG.warn("D3Sound: настройки не прочитались, беру значения по умолчанию: {}", error.toString());
		}
		return new D3Config();
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException error) {
			D3SoundEngine.LOG.warn("D3Sound: настройки не сохранились: {}", error.toString());
		}
	}
}
