package dev.cvds.feature;

import com.cobblemon.mod.common.api.pokemon.PokemonSortMode;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.client.storage.ClientPC;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.net.messages.server.storage.SwapPCPartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.party.MovePartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.party.ReleasePartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.party.SwapPartyPokemonPacket;
import com.cobblemon.mod.common.net.messages.server.storage.pc.*;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.joml.Vector4f;

/**
 * Cobblemon PC: boxes + party as JSON, and moves/swaps/releases/sorts through Cobblemon's own
 * packets. The server only accepts PC packets while the player has a PC open (it links the
 * player to that PC), so actions require the PC screen; browsing works from the cached copy.
 */
final class Pc {
	private Pc() {}
	private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);

	static ClientPC currentPc(Minecraft mc) {
		if (mc.screen instanceof PCGUI gui) return gui.getPc();
		var stores = CobblemonClient.INSTANCE.getStorage().getPcStores();
		return stores.isEmpty() ? null : stores.values().iterator().next();
	}

	static JsonObject json(Minecraft mc) {
		JsonObject o = new JsonObject();
		JsonObject items = new JsonObject();
		boolean open = mc.screen instanceof PCGUI;
		o.addProperty("open", open);
		ClientParty party = CobblemonClient.INSTANCE.getStorage().getParty();
		JsonArray partyJson = new JsonArray();
		for (int i = 0; i < 6; i++) partyJson.add(mon(i < party.getSlots().size() ? party.getSlots().get(i) : null, items));
		o.add("party", partyJson);
		ClientPC pc = currentPc(mc);
		if (pc != null) {
			o.addProperty("pcId", pc.getUuid().toString());
			JsonArray boxes = new JsonArray();
			for (var box : pc.getBoxes()) {
				JsonObject b = new JsonObject();
				b.addProperty("name", box.getName() == null ? "" : box.getName().getString());
				JsonArray slots = new JsonArray();
				for (Pokemon p : box.getSlots()) slots.add(mon(p, items));
				b.add("slots", slots);
				boxes.add(b);
			}
			o.add("boxes", boxes);
		}
		o.add("items", items);
		JsonArray modes = new JsonArray();
		for (PokemonSortMode m : PokemonSortMode.values()) modes.add(m.name());
		o.add("sortModes", modes);
		return o;
	}

	static JsonObject mon(Pokemon p, JsonObject items) {
		JsonObject m = new JsonObject();
		if (p == null) return m;
		m.addProperty("id", p.getUuid().toString());
		m.addProperty("name", p.getDisplayName(false).getString());
		m.addProperty("species", p.getSpecies().getTranslatedName().getString());
		m.addProperty("dex", p.getSpecies().getNationalPokedexNumber());
		m.addProperty("lvl", p.getLevel());
		m.addProperty("hp", p.getCurrentHealth());
		m.addProperty("maxHp", p.getMaxHealth());
		m.addProperty("shiny", p.getShiny());
		m.addProperty("gender", p.getGender().name());
		JsonArray types = new JsonArray();
		for (var t : p.getTypes()) types.add(t.getName());
		m.add("types", types);
		m.addProperty("nature", I18n.get(p.getNature().getDisplayName()));
		m.addProperty("ability", I18n.get(p.getAbility().getDisplayName()));
		JsonObject ivs = new JsonObject();
		int total = 0;
		for (Stats s : new Stats[]{Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED}) {
			Integer v = p.getIvs().get(s);
			int iv = v == null ? 0 : v;
			ivs.addProperty(s.name(), iv);
			total += iv;
		}
		m.add("ivs", ivs);
		m.addProperty("ivTotal", total);
		JsonArray moves = new JsonArray();
		for (var mv : p.getMoveSet().getMoves()) moves.add(mv.getDisplayName().getString());
		m.add("moves", moves);
		var held = p.getHeldItem$common();
		if (held != null && !held.isEmpty()) m.addProperty("held", held.getHoverName().getString());
		try {
			m.addProperty("k", Snapshot.describe(PokemonItem.from(p, 1, WHITE), items));
		} catch (Throwable t) {
			// no model icon: the UI falls back to the name
		}
		return m;
	}

	/** A slot reference from the UI: {"type":"pc","box":b,"slot":s} or {"type":"party","slot":s}. */
	record Ref(boolean pc, int box, int slot) {
		static Ref of(JsonObject o) {
			boolean pc = o.get("type").getAsString().equals("pc");
			return new Ref(pc, pc ? o.get("box").getAsInt() : -1, o.get("slot").getAsInt());
		}
		PCPosition pcPos() { return new PCPosition(box, slot); }
		PartyPosition partyPos() { return new PartyPosition(slot); }
	}

	static Pokemon at(ClientPC pc, Ref r) {
		if (r.pc()) {
			if (pc == null || r.box() < 0 || r.box() >= pc.getBoxes().size()) throw new IllegalArgumentException("no such box");
			var slots = pc.getBoxes().get(r.box()).getSlots();
			if (r.slot() < 0 || r.slot() >= slots.size()) throw new IllegalArgumentException("no such slot");
			return slots.get(r.slot());
		}
		if (r.slot() < 0 || r.slot() > 5) throw new IllegalArgumentException("party slot 0-5");
		var slots = CobblemonClient.INSTANCE.getStorage().getParty().getSlots();
		return r.slot() < slots.size() ? slots.get(r.slot()) : null;
	}

	static JsonObject action(Minecraft mc, JsonObject a) {
		String op = a.get("op").getAsString();
		ClientPC pc = currentPc(mc);
		boolean open = mc.screen instanceof PCGUI;
		switch (op) {
			case "move" -> {
				Ref from = Ref.of(a.getAsJsonObject("from")), to = Ref.of(a.getAsJsonObject("to"));
				if ((from.pc() || to.pc()) && !open) return Api.error("open a PC first");
				Pokemon src = at(pc, from), dst = at(pc, to);
				if (src == null) return Api.error("nothing there");
				if (from.equals(to)) return Api.ok();
				if (from.pc() && to.pc()) {
					if (dst == null) new MovePCPokemonPacket(src.getUuid(), from.pcPos(), to.pcPos()).sendToServer();
					else new SwapPCPokemonPacket(src.getUuid(), from.pcPos(), dst.getUuid(), to.pcPos()).sendToServer();
				} else if (from.pc()) {
					if (dst == null) new MovePCPokemonToPartyPacket(src.getUuid(), from.pcPos(), to.partyPos()).sendToServer();
					else new SwapPCPartyPokemonPacket(dst.getUuid(), to.partyPos(), src.getUuid(), from.pcPos()).sendToServer();
				} else if (to.pc()) {
					if (dst == null) new MovePartyPokemonToPCPacket(src.getUuid(), from.partyPos(), to.pcPos()).sendToServer();
					else new SwapPCPartyPokemonPacket(src.getUuid(), from.partyPos(), dst.getUuid(), to.pcPos()).sendToServer();
				} else {
					if (dst == null) new MovePartyPokemonPacket(src.getUuid(), from.partyPos(), to.partyPos()).sendToServer();
					else new SwapPartyPokemonPacket(src.getUuid(), from.partyPos(), dst.getUuid(), to.partyPos()).sendToServer();
				}
			}
			case "release" -> {
				Ref r = Ref.of(a.getAsJsonObject("at"));
				if (r.pc() && !open) return Api.error("open a PC first");
				Pokemon p = at(pc, r);
				// The UI must name the exact Pokémon it showed, so a stale view can't release the wrong one.
				if (p == null || !p.getUuid().toString().equals(a.get("id").getAsString())) return Api.error("that Pokémon moved; try again");
				if (r.pc()) new ReleasePCPokemonPacket(p.getUuid(), r.pcPos()).sendToServer();
				else new ReleasePartyPokemonPacket(p.getUuid(), r.partyPos()).sendToServer();
			}
			case "sort" -> {
				if (!open || pc == null) return Api.error("open a PC first");
				int box = a.get("box").getAsInt();
				if (box < 0 || box >= pc.getBoxes().size()) return Api.error("no such box");
				PokemonSortMode mode = PokemonSortMode.valueOf(a.get("mode").getAsString());
				new SortPCBoxPacket(pc.getUuid(), box, mode, a.has("desc") && a.get("desc").getAsBoolean()).sendToServer();
			}
			default -> { return Api.error("unknown pc op " + op); }
		}
		return Api.ok();
	}
}
