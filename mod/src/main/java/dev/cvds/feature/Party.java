package dev.cvds.feature;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** Cobblemon party. Only loaded when Cobblemon is present. */
final class Party {
	private Party() {}

	static JsonObject json(JsonObject items) {
		var storage = CobblemonClient.INSTANCE.getStorage();
		JsonObject o = new JsonObject();
		o.addProperty("sel", storage.getSelectedSlot());
		JsonArray slots = new JsonArray();
		var party = storage.getParty();
		for (int i = 0; i < 6; i++) {
			Pokemon p = i < party.getSlots().size() ? party.getSlots().get(i) : null;
			if (p == null) { slots.add(new JsonObject()); continue; }
			JsonObject s = new JsonObject();
			s.addProperty("id", p.getUuid().toString());
			try { s.addProperty("k", Snapshot.describe(com.cobblemon.mod.common.item.PokemonItem.from(p, 1, new org.joml.Vector4f(1, 1, 1, 1)), items)); } catch (Throwable ignored) {}
			s.addProperty("name", p.getDisplayName(false).getString());
			s.addProperty("species", p.getSpecies().getName());
			s.addProperty("lvl", p.getLevel());
			s.addProperty("hp", p.getCurrentHealth());
			s.addProperty("maxHp", p.getMaxHealth());
			s.addProperty("shiny", p.getShiny());
			s.addProperty("fainted", p.isFainted());
			var status = p.getStatus();
			if (status != null) s.addProperty("status", status.getStatus().getShowdownName());
			JsonArray types = new JsonArray();
			for (var t : p.getTypes()) types.add(t.getName());
			s.add("types", types);
			var held = p.getHeldItem$common();
			if (held != null && !held.isEmpty()) {
				s.addProperty("held", held.getHoverName().getString());
				s.addProperty("hk", Snapshot.describe(held, items));
			}
			// The PC's details (nature, ability, IVs, moves) so a tap on the card can show the summary.
			JsonObject d = Pc.mon(p, items);
			for (String f : new String[]{"dex", "gender", "nature", "ability", "ivs", "ivTotal", "moves"}) if (d.has(f)) s.add(f, d.get(f));
			slots.add(s);
		}
		o.add("slots", slots);
		return o;
	}

	static void select(int slot) {
		if (slot < 0 || slot > 5) throw new IllegalArgumentException("party slot 0-5");
		CobblemonClient.INSTANCE.getStorage().setSelectedSlot(slot);
	}

	static boolean sendOut(Minecraft mc, int slot) {
		select(slot);
		return Keys.press(mc, "key.cobblemon.throwpartypokemon");
	}
}
