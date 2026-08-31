package dev.d3sound.mc.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.d3sound.mc.client.gui.D3Overlay;
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
				D3Config config = D3Config.get();
				config.enabled = !config.enabled;
				config.save();
				engine.enabled = config.enabled;
				if (!engine.enabled) engine.stopAll();
				say(client, engine.enabled
					? "D3Sound: свой движок включён"
					: "D3Sound: вернул ванильный звук");
			}

			while (infoKey.consumeClick()) {
				say(client, engine.status());
			}

			engine.clientTick(client);
		});

		D3Overlay.register();
		D3SoundEngine.LOG.info("D3Sound: клиент инициализирован");
	}

	private static void say(Minecraft client, String text) {
		if (client.player != null) client.player.sendSystemMessage(Component.literal(text));
		D3SoundEngine.LOG.info(text);
	}
}
