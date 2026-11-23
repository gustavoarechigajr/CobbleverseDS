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

	public static JsonArray list(Minecraft mc) {
		JsonArray arr = new JsonArray();
		for (KeyMapping k : mc.options.keyMappings) {
			JsonObject o = new JsonObject();
			o.addProperty("id", k.getName());
			o.addProperty("label", I18n.get(k.getName()));
			o.addProperty("cat", I18n.get(k.getCategory()));
			o.addProperty("bound", k.isUnbound() ? "" : k.getTranslatedKeyMessage().getString());
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

	private static final List<KeyMapping> RELEASE = new ArrayList<>();

	public static void tick() {
		synchronized (HELD) {
			for (KeyMapping k : RELEASE) k.setDown(false);
			RELEASE.clear();
			RELEASE.addAll(HELD);
			HELD.clear();
		}
	}
}
