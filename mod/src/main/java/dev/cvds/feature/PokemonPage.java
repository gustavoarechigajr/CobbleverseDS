package dev.cvds.feature;

import com.cobblemon.mod.common.api.moves.BenchedMove;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.evolution.EvolutionDisplay;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.net.messages.server.BenchMovePacket;
import com.cobblemon.mod.common.net.messages.server.RequestMoveSwapPacket;
import com.cobblemon.mod.common.net.messages.server.pokemon.update.SetNicknamePacket;
import com.cobblemon.mod.common.net.messages.server.pokemon.update.evolution.AcceptEvolutionPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The panel's Pokémon page: everything the Summary shows, and the Summary's edits sent as the same
 * Cobblemon packets (reorder moves, swap in a move it knew before, evolve, rename). Party Pokémon only.
 * Client thread. Cobblemon only.
 */
final class PokemonPage {
	private PokemonPage() {}
	static final Stats[] SIX = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};

	private static Pokemon party(int slot) {
		var slots = CobblemonClient.INSTANCE.getStorage().getParty().getSlots();
		return slot >= 0 && slot < slots.size() ? slots.get(slot) : null;
	}

	private static JsonObject move(MoveTemplate t, int pp, int maxPp) {
		JsonObject m = new JsonObject();
		m.addProperty("id", t.getName());
		m.addProperty("name", t.getDisplayName().getString());
		m.addProperty("type", t.getElementalType().getName());
		m.addProperty("cat", t.getDamageCategory().getName());
		m.addProperty("power", t.getPower());
		m.addProperty("acc", t.getAccuracy());
		try { m.addProperty("desc", t.getDescription().getString()); } catch (Throwable ignored) {}
		if (maxPp > 0) { m.addProperty("pp", pp); m.addProperty("maxPp", maxPp); }
		return m;
	}

	static JsonObject json(int slot) {
		Pokemon p = party(slot);
		if (p == null) return Api.error("no Pokémon in that party slot");
		JsonObject items = new JsonObject();
		JsonObject o = Pc.mon(p, items); // name, species, level, HP, types, nature, ability, IVs, held, icon key...
		o.addProperty("ok", true);
		o.addProperty("slot", slot);
		var nick = p.getNickname();
		o.addProperty("nickname", nick == null ? "" : nick.getString());
		o.addProperty("friendship", p.getFriendship());
		o.addProperty("xp", p.getExperience());
		o.addProperty("fainted", p.isFainted());
		JsonObject evs = new JsonObject();
		int evTotal = 0;
		for (Stats s : SIX) { Integer v = p.getEvs().get(s); int ev = v == null ? 0 : v; evs.addProperty(s.name(), ev); evTotal += ev; }
		o.add("evs", evs);
		o.addProperty("evTotal", evTotal);
		JsonArray moves = new JsonArray();
		for (Move mv : p.getMoveSet().getMoves()) moves.add(move(mv.getTemplate(), mv.getCurrentPp(), mv.getMaxPp()));
		o.add("moveList", moves);
		JsonArray bench = new JsonArray();
		for (BenchedMove b : p.getBenchedMoves()) bench.add(move(b.getMoveTemplate(), 0, 0));
		o.add("bench", bench);
		JsonArray evo = new JsonArray();
		try {
			int i = 0;
			for (EvolutionDisplay d : p.getEvolutionProxy().client()) {
				JsonObject e = new JsonObject();
				e.addProperty("i", i++);
				e.addProperty("name", d.getSpecies().getTranslatedName().getString());
				try { e.addProperty("k", ItemKeys.key(PokemonItem.from(d.getSpecies(), d.getAspects(), 1, new org.joml.Vector4f(1, 1, 1, 1)))); } catch (Throwable ignored) {}
				evo.add(e);
			}
		} catch (Throwable ignored) {}
		o.add("evolutions", evo);
		o.add("items", items);
		return o;
	}

	static JsonObject act(Minecraft mc, JsonObject a) {
		int slot = a.get("slot").getAsInt();
		Pokemon p = party(slot);
		if (p == null) return Api.error("no Pokémon in that party slot");
		String op = a.get("op").getAsString();
		switch (op) {
			case "swapMoves" -> {
				int x = a.get("a").getAsInt(), y = a.get("b").getAsInt();
				int n = p.getMoveSet().getMoves().size();
				if (x < 0 || y < 0 || x >= n || y >= n || x == y) return Api.error("can't move that move there");
				new RequestMoveSwapPacket(x, y, slot).sendToServer();
			}
			case "benchMove" -> {
				MoveTemplate from = Moves.INSTANCE.getByName(a.get("from").getAsString());
				MoveTemplate to = Moves.INSTANCE.getByName(a.get("to").getAsString());
				if (from == null || to == null) return Api.error("unknown move");
				new BenchMovePacket(true, p.getUuid(), from, to).sendToServer();
			}
			case "evolve" -> {
				List<EvolutionDisplay> list = new ArrayList<>(p.getEvolutionProxy().client());
				int i = a.get("i").getAsInt();
				if (i < 0 || i >= list.size()) return Api.error("that evolution isn't available");
				new AcceptEvolutionPacket(p, list.get(i)).sendToServer();
			}
			case "rename" -> {
				String name = a.get("name").getAsString().trim();
				if (name.length() > 12) name = name.substring(0, 12); // Cobblemon's nickname limit
				new SetNicknamePacket(p.getUuid(), true, name.isEmpty() ? null : name).sendToServer();
			}
			default -> { return Api.error("unknown Pokémon op " + op); }
		}
		return Api.ok();
	}
}
