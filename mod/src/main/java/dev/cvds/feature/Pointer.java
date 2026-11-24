package dev.cvds.feature;

import com.google.gson.JsonObject;
import dev.cvds.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

/**
 * The panel's touchpad: moves the game's mouse cursor and clicks, for screens the controller mod
 * can't drive (Cobblemon's Summary moves page, the Pokédex, the world map...). Runs on the client thread.
 */
public final class Pointer {
	private Pointer() {}
	private static volatile long lastUse = 0;
	static final long SHOW_MS = 4000;

	/** dx, dy are in GUI pixels (the size things appear on screen at the current GUI scale). */
	static JsonObject act(Minecraft mc, JsonObject a) {
		if (mc.screen == null) return Api.error("no screen is open: the touchpad works on menus");
		var win = mc.getWindow();
		long h = win.getWindow();
		var mh = (MouseHandlerAccessor) mc.mouseHandler;
		double scale = win.getGuiScale();
		String op = a.get("op").getAsString();
		lastUse = System.currentTimeMillis();
		switch (op) {
			case "move" -> {
				double x = clamp(mc.mouseHandler.xpos() + a.get("dx").getAsDouble() * scale, win.getScreenWidth() - 1);
				double y = clamp(mc.mouseHandler.ypos() + a.get("dy").getAsDouble() * scale, win.getScreenHeight() - 1);
				mh.cvds$onMove(h, x, y);
			}
			case "click" -> {
				int b = a.has("button") ? a.get("button").getAsInt() : 0;
				mh.cvds$onPress(h, b, GLFW.GLFW_PRESS, 0);
				mh.cvds$onPress(h, b, GLFW.GLFW_RELEASE, 0);
			}
			case "scroll" -> mh.cvds$onScroll(h, 0, a.get("dy").getAsDouble());
			case "center" -> mh.cvds$onMove(h, win.getScreenWidth() / 2.0, win.getScreenHeight() / 2.0);
			case "escape" -> mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); // what Esc / B does: back or close
			default -> { return Api.error("unknown pointer op " + op); }
		}
		return Api.ok();
	}

	private static double clamp(double v, double max) { return Math.max(0, Math.min(max, v)); }

	/** Draws a cursor where the panel's touchpad is pointing (Zalith draws its own only for real touches). */
	public static void draw(Minecraft mc, GuiGraphics g) {
		if (mc.screen == null || System.currentTimeMillis() - lastUse > SHOW_MS) return;
		var win = mc.getWindow();
		int x = (int) (mc.mouseHandler.xpos() * win.getGuiScaledWidth() / win.getScreenWidth());
		int y = (int) (mc.mouseHandler.ypos() * win.getGuiScaledHeight() / win.getScreenHeight());
		// a small arrow: black outline, white fill, tip at (x, y)
		for (int i = 0; i < 10; i++) g.fill(x - 1, y + i - 1, x + i / 2 + 2, y + i + 1, 0xFF000000);
		for (int i = 0; i < 8; i++) g.fill(x, y + i, x + i / 2 + 1, y + i + 1, 0xFFFFFFFF);
		g.fill(x + 2, y + 8, x + 4, y + 12, 0xFF000000);
		g.fill(x + 2, y + 8, x + 3, y + 11, 0xFFFFFFFF);
	}

	public static boolean visible(Minecraft mc) { return mc.screen != null && System.currentTimeMillis() - lastUse <= SHOW_MS; }
}
