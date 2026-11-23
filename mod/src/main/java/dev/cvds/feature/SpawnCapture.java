package dev.cvds.feature;

import java.util.List;
import net.minecraft.network.chat.Component;

/** Collects messages on the server thread while a spawn check runs (see ServerPlayerMixin). */
public final class SpawnCapture {
	private SpawnCapture() {}
	private static final ThreadLocal<List<String>> SINK = new ThreadLocal<>();

	static void begin(List<String> sink) { SINK.set(sink); }
	static void end() { SINK.remove(); }

	/** True if the message was captured (and must not reach the player's chat). */
	public static boolean offer(Component message) {
		List<String> sink = SINK.get();
		if (sink == null) return false;
		sink.add(message.getString());
		return true;
	}
}
