package dev.cvds.feature;

import com.google.gson.JsonObject;
import dev.cvds.mixin.MouseHandlerAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Mouse input from the panel's menu mirror: a touch at (u, v) — fractions of the game screen, 0..1 —
 * becomes the game's own mouse events at that spot, so any menu works by tapping its picture.
 * Runs on the client thread.
 */
public final class Pointer {
	private Pointer() {}

	static JsonObject act(Minecraft mc, JsonObject a) {
		if (mc.screen == null) return Api.error("no menu is open");
		var win = mc.getWindow();
		long h = win.getWindow();
		var mh = (MouseHandlerAccessor) mc.mouseHandler;
		String op = a.get("op").getAsString();
		int button = a.has("button") ? a.get("button").getAsInt() : 0;
		if (a.has("u") && a.has("v")) {
			double x = clamp(a.get("u").getAsDouble()) * (win.getScreenWidth() - 1);
			double y = clamp(a.get("v").getAsDouble()) * (win.getScreenHeight() - 1);
			mh.cvds$onMove(h, x, y);
		}
		switch (op) {
			case "move", "drag" -> {} // the move above is the whole action (a drag while a button is held)
			case "press" -> mh.cvds$onPress(h, button, GLFW.GLFW_PRESS, 0);
			case "release" -> mh.cvds$onPress(h, button, GLFW.GLFW_RELEASE, 0);
			case "tap" -> {
				mh.cvds$onPress(h, button, GLFW.GLFW_PRESS, 0);
				mh.cvds$onPress(h, button, GLFW.GLFW_RELEASE, 0);
			}
			case "scroll" -> mh.cvds$onScroll(h, 0, a.get("dy").getAsDouble());
			case "escape" -> mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); // what Esc / B does: back or close
			default -> { return Api.error("unknown pointer op " + op); }
		}
		return Api.ok();
	}

	private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
