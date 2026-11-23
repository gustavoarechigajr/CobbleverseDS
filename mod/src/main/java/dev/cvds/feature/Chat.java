package dev.cvds.feature;

import com.google.gson.JsonArray;
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
