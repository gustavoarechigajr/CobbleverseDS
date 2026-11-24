package dev.cvds.feature;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.PokemonItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Pokémon near the player for the map (Cobblemon only). */
final class MapMons {
	private MapMons() {}

	static JsonArray nearby(Minecraft mc, double radius) {
		JsonArray a = new JsonArray();
		double r2 = radius * radius;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof PokemonEntity pe) || e.distanceToSqr(mc.player) > r2) continue;
			var p = pe.getPokemon();
			JsonObject o = new JsonObject();
			o.addProperty("name", p.getSpecies().getTranslatedName().getString());
			o.addProperty("lvl", p.getLevel());
			o.addProperty("x", e.getX());
			o.addProperty("z", e.getZ());
			o.addProperty("wild", pe.getOwnerUUID() == null);
			o.addProperty("shiny", p.getShiny());
			try { o.addProperty("k", ItemKeys.key(PokemonItem.from(p, 1))); } catch (Throwable ignored) {}
			a.add(o);
			if (a.size() >= 40) break;
		}
		return a;
	}
}
