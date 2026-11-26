package dev.cvds.feature;

import com.google.gson.JsonObject;
import dev.cvds.mixin.MouseHandlerAccessor;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Mouse input from the panel's menu mirror: a touch at (u, v) — fractions of the game screen, 0..1 —
 * becomes the game's own mouse events at that spot, so any menu works by tapping its picture.
 * Runs on the client thread.
 *
 * Some screens (Xaero's world map) only work out what is under the mouse while drawing, so a click sent
 * in the same frame as its move lands where the mouse was before. Clicks and scrolls after a move therefore
 * wait for one drawn frame; later actions queue behind them so the order never changes.
 */
public final class Pointer {
	private Pointer() {}

	private static final Runnable FRAME = () -> {}; // marker: wait for the next drawn frame
	private static final ArrayDeque<Runnable> queue = new ArrayDeque<>();

	static JsonObject act(Minecraft mc, JsonObject a) {
		if (mc.screen == null) return Api.error("no menu is open");
		String op = a.get("op").getAsString();
		int button = a.has("button") ? a.get("button").getAsInt() : 0;
		Runnable action = switch (op) {
			case "move", "drag" -> null; // the move is the whole action (a drag while a button is held)
			case "press" -> () -> press(mc, button, GLFW.GLFW_PRESS);
			case "release" -> () -> press(mc, button, GLFW.GLFW_RELEASE);
			case "tap" -> () -> { press(mc, button, GLFW.GLFW_PRESS); press(mc, button, GLFW.GLFW_RELEASE); };
			case "scroll" -> { double dy = a.get("dy").getAsDouble(); yield () -> mouse(mc).cvds$onScroll(win(mc), 0, dy); }
			case "escape" -> () -> { if (mc.screen != null) mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); }; // what Esc / B does
			default -> null;
		};
		if (action == null && !op.equals("move") && !op.equals("drag")) return Api.error("unknown pointer op " + op);
		if (a.has("u") && a.has("v")) {
			double u = clamp(a.get("u").getAsDouble()), v = clamp(a.get("v").getAsDouble());
			queue.add(() -> {
				var w = mc.getWindow();
				mouse(mc).cvds$onMove(w.getWindow(), u * (w.getScreenWidth() - 1), v * (w.getScreenHeight() - 1));
			});
			if (action != null && !op.equals("escape")) queue.add(FRAME);
		}
		if (action != null) queue.add(action);
		drain();
		return Api.ok();
	}

	/** Render thread, after each drawn frame: carry on with actions that were waiting for it. */
	public static void afterFrame() {
		if (queue.isEmpty()) return;
		if (queue.peek() == FRAME) queue.poll();
		drain();
	}

	private static void drain() {
		Runnable r;
		while ((r = queue.peek()) != null && r != FRAME) {
			queue.poll();
			if (Minecraft.getInstance().screen != null) r.run(); // the menu closed meanwhile: drop it
		}
	}

	private static void press(Minecraft mc, int button, int action) { mouse(mc).cvds$onPress(win(mc), button, action, 0); }
	private static MouseHandlerAccessor mouse(Minecraft mc) { return (MouseHandlerAccessor) mc.mouseHandler; }
	private static long win(Minecraft mc) { return mc.getWindow().getWindow(); }
	private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
