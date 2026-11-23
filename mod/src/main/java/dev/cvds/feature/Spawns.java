package dev.cvds.feature;

import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.joml.Vector4f;

/**
 * "What can spawn here?" In singleplayer the mod runs Cobblemon's own /checkspawn on the
 * integrated server as the player (read-only, so it works without cheats) and parses the
 * result; the Pokédex status comes from Cobblemon's client data. Not available on servers.
 */
final class Spawns {
	private Spawns() {}
	static final String[] BUCKETS = {"common", "uncommon", "rare", "ultra-rare"};

	/** Runs on an HTTP thread: waits for the server thread, then the client thread. */
	static JsonObject query(Minecraft mc) throws Exception {
		var server = mc.getSingleplayerServer();
		if (server == null) return Api.error("spawn info needs singleplayer");
		Map<String, List<String>> raw = server.submit(() -> {
			Map<String, List<String>> out = new LinkedHashMap<>();
			var players = server.getPlayerList().getPlayers();
			if (players.isEmpty()) return out;
			var player = players.get(0);
			for (String bucket : BUCKETS) {
				List<String> lines = new ArrayList<>();
				CommandSource sink = new CommandSource() {
					@Override public void sendSystemMessage(Component c) { lines.add(c.getString()); }
					@Override public boolean acceptsSuccess() { return true; }
					@Override public boolean acceptsFailure() { return true; }
					@Override public boolean shouldInformAdmins() { return false; }
				};
				var src = new CommandSourceStack(sink, player.position(), player.getRotationVector(), player.serverLevel(), 4,
					player.getName().getString(), player.getDisplayName(), server, player);
				server.getCommands().performPrefixedCommand(src, "checkspawn " + bucket);
				out.put(bucket, lines);
			}
			return out;
		}).get(10, TimeUnit.SECONDS);
		return mc.submit(() -> build(raw)).get(5, TimeUnit.SECONDS);
	}

	static JsonObject build(Map<String, List<String>> raw) {
		JsonObject o = Api.ok();
		JsonObject items = new JsonObject();
		var dex = CobblemonClient.INSTANCE.getClientPokedexData();
		JsonArray buckets = new JsonArray();
		for (var e : raw.entrySet()) {
			JsonObject b = new JsonObject();
			b.addProperty("bucket", e.getKey());
			JsonArray list = new JsonArray();
			for (String[] entry : dev.cvds.inv.SpawnText.parse(e.getValue())) {
				JsonObject s = new JsonObject();
				s.addProperty("name", entry[0]);
				s.addProperty("pct", Double.parseDouble(entry[1]));
				Species sp = find(entry[0]);
				if (sp != null) {
					s.addProperty("dex", sp.getNationalPokedexNumber());
					s.addProperty("types", sp.getPrimaryType().getName() + (sp.getSecondaryType() != null ? "/" + sp.getSecondaryType().getName() : ""));
					var rec = dex == null ? null : dex.getSpeciesRecords().get(sp.getResourceIdentifier());
					PokedexEntryProgress k = rec == null ? PokedexEntryProgress.NONE : rec.getKnowledge();
					s.addProperty("dexState", k.name());
					try { s.addProperty("k", Snapshot.describe(PokemonItem.from(sp, new HashSet<String>(), 1, new Vector4f(1, 1, 1, 1)), items)); } catch (Throwable ignored) {}
				}
				list.add(s);
			}
			b.add("list", list);
			if (list.isEmpty() && !e.getValue().isEmpty()) b.addProperty("note", String.join(" ", e.getValue()));
			buckets.add(b);
		}
		o.add("buckets", buckets);
		o.add("items", items);
		return o;
	}

	private static Map<String, Species> byName;

	/** Species by display name ("Mr. Mime"), falling back to the plain id ("mrmime"). */
	static Species find(String name) {
		if (byName == null) {
			Map<String, Species> m = new HashMap<>();
			for (Species s : PokemonSpecies.getSpecies()) m.put(s.getTranslatedName().getString().toLowerCase(Locale.ROOT), s);
			byName = m;
		}
		Species s = byName.get(name.toLowerCase(Locale.ROOT));
		if (s != null) return s;
		// form-prefixed names such as "Alolan Vulpix": use the last word
		String[] words = name.split(" ");
		s = byName.get(words[words.length - 1].toLowerCase(Locale.ROOT));
		return s != null ? s : PokemonSpecies.getByName(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
	}
}
