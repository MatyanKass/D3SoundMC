package dev.d3sound.mc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.d3sound.mc.audio.VoxelAcoustics;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Точка входа клиента.
 *
 * Движок звука живёт в {@link D3SoundEngine}; здесь только его тик и пара
 * горячих клавиш: переключение нашего звука на ванильный и показ того,
 * что движок сейчас думает о помещении вокруг игрока.
 */
public final class D3SoundClient implements ClientModInitializer {
	public static final String MOD_ID = "d3sound";

	private static KeyMapping toggleKey;
	private static KeyMapping infoKey;

	@Override
	public void onInitializeClient() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.d3sound.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC));
		infoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.d3sound.info", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			D3SoundEngine engine = D3SoundEngine.get();

			while (toggleKey.consumeClick()) {
				engine.enabled = !engine.enabled;
				if (!engine.enabled) engine.stopAll();
				say(client, engine.enabled
					? "D3Sound: свой движок включён"
					: "D3Sound: вернул ванильный звук");
			}

			while (infoKey.consumeClick()) {
				VoxelAcoustics.Probe probe = engine.lastProbe();
				if (probe == null) {
					say(client, "D3Sound: помещение ещё не измерено");
				} else {
					say(client, String.format(
						"D3Sound: RT60 %.2f с · пробег %.1f м · открытость %d%% · поглощение %.2f · источников %d",
						probe.rt60[2], probe.meanFreePath, Math.round(probe.openness * 100),
						probe.meanAbsorption, engine.activeSources()));
				}
			}

			engine.clientTick(client);
		});

		D3SoundEngine.LOG.info("D3Sound: клиент инициализирован");
	}

	private static void say(Minecraft client, String text) {
		if (client.player != null) client.player.sendSystemMessage(Component.literal(text));
		D3SoundEngine.LOG.info(text);
	}
}
