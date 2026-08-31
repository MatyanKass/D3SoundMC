package dev.d3sound.mc.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа клиента.
 *
 * План движка: перехватить воспроизведение звука у самого источника, посчитать
 * распространение по геометрии мира (воксельная трассировка по блокам с их
 * акустическими свойствами) и отрендерить всё своим бинауральным микшером,
 * а не отдавать позицию в OpenAL «как есть».
 */
public final class D3SoundClient implements ClientModInitializer {
	public static final String MOD_ID = "d3sound";
	public static final Logger LOG = LoggerFactory.getLogger("D3Sound");

	@Override
	public void onInitializeClient() {
		LOG.info("D3Sound: клиент инициализирован");
	}
}
