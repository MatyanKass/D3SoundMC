package dev.d3sound.mc.client.gui;

import dev.d3sound.mc.audio.Budget;
import dev.d3sound.mc.client.D3Config;
import dev.d3sound.mc.client.D3SoundEngine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Счётчик на экране: во что сейчас обходится звук и что движок думает о
 * помещении вокруг игрока. Включается в настройках, по умолчанию скрыт.
 */
public final class D3Overlay {
	private D3Overlay() {}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("d3sound", "profiler"), (graphics, delta) -> {
			if (!D3Config.get().overlay) return;
			Minecraft client = Minecraft.getInstance();
			if (client.font == null || client.level == null) return;

			D3SoundEngine engine = D3SoundEngine.get();
			Budget budget = engine.budget();
			float[] rt60 = engine.solver().rt60;

			String[] lines = {
				"D3Sound " + (engine.enabled ? "вкл" : "выкл"),
				String.format("источников %d · лучей %d · отскоков %d",
					engine.activeSources(), budget.rays(), budget.bounces()),
				String.format("качество %d%% · движок %.1f%% ЦП (потолок %.0f%%) · прогон %.1f мс",
					Math.round(budget.quality() * 100), budget.ownShare() * 100,
					budget.ownShareLimit * 100, budget.solveMs()),
				String.format("система %d%% · ядер %d", Math.round(budget.load() * 100), budget.cores()),
				String.format("RT60 %.2f с · пробег %.1f м · открытость %.0f%% · хвост %.0f%% · скорость %.0f м/с",
					rt60.length > 2 ? rt60[2] : 0f, engine.solver().meanFreePath,
					engine.solver().openness * 100, engine.mixer().wet() * 100,
					engine.mixer().air.speedOfSound),
			};
			int y = 4;
			for (String line : lines) {
				graphics.text(client.font, line, 4, y, 0xFFA0E0FF, true);
				y += 10;
			}
		});
	}
}
