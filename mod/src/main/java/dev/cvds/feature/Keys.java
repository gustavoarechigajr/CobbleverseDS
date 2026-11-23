package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cvds.mixin.KeyMappingAccessor;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/** Lists every key mapping (vanilla + mods) and "presses" one for a tick. */
public final class Keys {
	private Keys() {}
	private static final List<KeyMapping> HELD = new ArrayList<>();
	/** Keys the panel is holding down until told to let go (sneak, sprint, zoom, auto-walk...). */
	private static final Set<String> STICKY = Collections.synchronizedSet(new LinkedHashSet<>());

	public static JsonArray list(Minecraft mc) {
		JsonArray arr = new JsonArray();
		for (KeyMapping k : mc.options.keyMappings) {
			JsonObject o = new JsonObject();
			o.addProperty("id", k.getName());
			o.addProperty("label", I18n.get(k.getName()));
			o.addProperty("cat", I18n.get(k.getCategory()));
			o.addProperty("bound", k.isUnbound() ? "" : k.getTranslatedKeyMessage().getString());
			o.addProperty("held", STICKY.contains(k.getName()));
			arr.add(o);
		}
		return arr;
	}

	/** Holds the key down until the end of the next tick and queues one click, like a real key tap. */
	public static boolean press(Minecraft mc, String id) {
		for (KeyMapping k : mc.options.keyMappings) {
			if (!k.getName().equals(id)) continue;
			k.setDown(true);
			KeyMappingAccessor acc = (KeyMappingAccessor) k;
			acc.cvds$setClickCount(acc.cvds$getClickCount() + 1);
			synchronized (HELD) { HELD.add(k); }
			return true;
		}
		return false;
	}

	/** Holds a key down (true) until released (false). */
	public static boolean hold(Minecraft mc, String id, boolean down) {
		for (KeyMapping k : mc.options.keyMappings) {
			if (!k.getName().equals(id)) continue;
			if (down) {
				if (STICKY.add(id)) {
					KeyMappingAccessor acc = (KeyMappingAccessor) k;
					acc.cvds$setClickCount(acc.cvds$getClickCount() + 1);
				}
				k.setDown(true);
			} else {
				STICKY.remove(id);
				k.setDown(false);
			}
			return true;
		}
		return false;
	}

	private static final List<KeyMapping> RELEASE = new ArrayList<>();

	public static void tick() {
		// Minecraft releases every key when a screen opens or focus changes: re-assert held ones.
		if (!STICKY.isEmpty()) {
			var mc = Minecraft.getInstance();
			if (mc.options != null) for (KeyMapping k : mc.options.keyMappings) if (STICKY.contains(k.getName())) k.setDown(true);
		}
		synchronized (HELD) {
			for (KeyMapping k : RELEASE) if (!STICKY.contains(k.getName())) k.setDown(false);
			RELEASE.clear();
			RELEASE.addAll(HELD);
			HELD.clear();
		}
	}
}
