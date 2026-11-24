package dev.cvds.feature;

import com.cobblemon.mod.common.client.gui.summary.Summary;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Cobblemon screens the controller mod can't navigate, driven from the panel. Summary: which Pokémon and
 * page are showing, and switching them. Only loaded when Cobblemon is present.
 */
final class Screens {
	private Screens() {}
	static final String[] TABS = {"Info", "Moves", "Stats", "Marks"};

	static boolean isSummary(Screen s) { return s instanceof Summary; }

	private static Object field(Object o, String name) throws Exception {
		Field f = Summary.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static JsonObject summary(Summary s) {
		JsonObject o = new JsonObject();
		try {
			o.addProperty("tab", (int) field(s, "mainScreenIndex"));
			o.addProperty("side", s.getSideScreenIndex());
			@SuppressWarnings("unchecked")
			var party = (java.util.List<Pokemon>) field(s, "party");
			Pokemon sel = s.getSelectedPokemon$common();
			JsonArray names = new JsonArray();
			int idx = -1;
			for (int i = 0; i < party.size(); i++) {
				Pokemon p = party.get(i);
				names.add(p == null ? "" : p.getDisplayName(false).getString());
				if (p != null && p == sel) idx = i;
			}
			o.add("party", names);
			o.addProperty("sel", idx);
		} catch (Exception e) {
			o.addProperty("error", e.toString());
		}
		return o;
	}

	static JsonObject act(Minecraft mc, JsonObject a) {
		try { return act0(mc, a); }
		catch (Exception e) { return Api.error(e.getClass().getSimpleName() + ": " + e.getMessage()); }
	}

	private static JsonObject act0(Minecraft mc, JsonObject a) throws Exception {
		if (!(mc.screen instanceof Summary s)) return Api.error("the summary isn't open");
		switch (a.get("op").getAsString()) {
			case "select" -> s.switchSelection(a.get("i").getAsInt());
			case "tab" -> {
				Method m = Summary.class.getDeclaredMethod("displayMainScreen", int.class);
				m.setAccessible(true);
				m.invoke(s, a.get("i").getAsInt());
			}
			case "close" -> s.onClose();
			default -> { return Api.error("unknown summary op"); }
		}
		return Api.ok();
	}
}
