package dev.d3sound.mc.client.gui;

import dev.d3sound.mc.client.Conflicts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Список модов, которые спорят с нами за звук, и чем именно. */
public final class D3ConflictsScreen extends OptionsSubScreen {
	private static final int ROW = 290;

	public D3ConflictsScreen(final Screen lastScreen, final Options options) {
		super(lastScreen, options, Component.translatable("d3sound.conflicts.title"));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;
		List<Conflicts.Found> found = Conflicts.result();
		if (found == null) {
			Conflicts.scanInBackground();
			this.list.addSmall(new StringWidget(ROW, 20,
				Component.translatable("d3sound.conflicts.scanning"), this.font), null);
			return;
		}
		if (found.isEmpty()) {
			this.list.addSmall(new StringWidget(ROW, 20,
				Component.translatable("d3sound.conflicts.none").withStyle(ChatFormatting.GREEN),
				this.font), null);
			return;
		}
		for (Conflicts.Found item : found) {
			ChatFormatting color = switch (item.level()) {
				case BLOCKING -> ChatFormatting.RED;
				case PARTIAL -> ChatFormatting.GOLD;
				case MINOR -> ChatFormatting.YELLOW;
			};
			String mark = switch (item.level()) {
				case BLOCKING -> "d3sound.conflicts.level.blocking";
				case PARTIAL -> "d3sound.conflicts.level.partial";
				case MINOR -> "d3sound.conflicts.level.minor";
			};
			this.list.addSmall(new StringWidget(ROW, 16,
				Component.literal(item.name() + " — ")
					.append(Component.translatable(mark)).withStyle(color),
				this.font), null);
			MultiLineTextWidget reason = new MultiLineTextWidget(
				Component.literal(item.reason()).withStyle(ChatFormatting.GRAY), this.font);
			reason.setMaxWidth(ROW);
			this.list.addSmall(reason, null);
		}
		this.list.addSmall(new MultiLineTextWidget(
			Component.translatable("d3sound.conflicts.hint").withStyle(ChatFormatting.DARK_GRAY),
			this.font).setMaxWidth(ROW), null);
	}

	/** Открыть с текущего экрана. */
	public static void open(Screen from) {
		Minecraft.getInstance().setScreenAndShow(new D3ConflictsScreen(from, Minecraft.getInstance().options));
	}
}
