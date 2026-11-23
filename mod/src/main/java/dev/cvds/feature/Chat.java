package dev.cvds.feature;

import com.google.gson.JsonArray;
import java.util.Optional;
import net.minecraft.network.chat.Style;
import dev.cvds.mixin.ChatComponentAccessor;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;

public final class Chat {
	private Chat() {}

	public static void send(Minecraft mc, String text) {
		text = text.strip();
		if (text.isEmpty() || mc.player == null) return;
		if (text.length() > 256) text = text.substring(0, 256);
		if (text.startsWith("/")) mc.player.connection.sendCommand(text.substring(1));
		else mc.player.connection.sendChat(text);
	}

	/** Most recent chat lines, oldest first. */
	/** Same messages as {@link #recent}, as [text, "#rrggbb" or "", bold] runs so the panel keeps chat colours. */
	public static JsonArray rich(Minecraft mc, int n) {
		JsonArray arr = new JsonArray();
		var all = ((ChatComponentAccessor) mc.gui.getChat()).cvds$getAllMessages();
		for (int i = Math.min(n, all.size()) - 1; i >= 0; i--) {
			JsonArray runs = new JsonArray();
			all.get(i).content().visit((Style style, String text) -> {
				if (text.isEmpty()) return Optional.empty();
				JsonArray run = new JsonArray();
				run.add(text);
				run.add(style.getColor() != null ? String.format("#%06x", style.getColor().getValue() & 0xffffff) : "");
				run.add(style.isBold());
				runs.add(run);
				return Optional.empty();
			}, Style.EMPTY);
			arr.add(runs);
		}
		return arr;
	}

	public static JsonArray recent(Minecraft mc, int n) {
		JsonArray arr = new JsonArray();
		var all = ((ChatComponentAccessor) mc.gui.getChat()).cvds$getAllMessages();
		for (int i = Math.min(n, all.size()) - 1; i >= 0; i--) {
			GuiMessage m = all.get(i);
			arr.add(m.content().getString());
		}
		return arr;
	}
}
