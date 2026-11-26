package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The Storage tab's Backpack view: what the carried Sophisticated Backpacks hold, grouped by item, and
 * moving items between them and the inventory without opening the backpack screen. Singleplayer only
 * (backpack contents live on the integrated server). Called from HTTP threads.
 */
final class BackpackView {
	private BackpackView() {}

	static JsonObject list(Minecraft mc) throws Exception {
		if (!Backpacks.LOADED) return Api.error("Sophisticated Backpacks isn't installed");
		return BattleBag.onServer(mc, sp -> {
			Map<String, JsonObject> items = new LinkedHashMap<>();
			int[] slots = {0, 0};
			java.util.Set<Object> packs = new java.util.HashSet<>();
			Backpacks.forEachSlot(sp, s -> {
				packs.add(s.handler());
				slots[0]++;
				if (s.stack().isEmpty()) { slots[1]++; return; }
				String k = ItemKeys.key(s.stack());
				JsonObject e = items.computeIfAbsent(k, key -> {
					JsonObject n = new JsonObject();
					n.addProperty("k", key);
					n.addProperty("name", s.stack().getHoverName().getString());
					n.addProperty("n", 0);
					return n;
				});
				e.addProperty("n", e.get("n").getAsInt() + s.stack().getCount());
			}, true);
			JsonObject o = Api.ok();
			JsonArray list = new JsonArray();
			items.values().forEach(list::add);
			o.add("list", list);
			o.addProperty("packs", packs.size());
			o.addProperty("slots", slots[0]);
			o.addProperty("free", slots[1]);
			return o;
		});
	}

	static JsonObject act(Minecraft mc, JsonObject a) throws Exception {
		if (!Backpacks.LOADED) return Api.error("Sophisticated Backpacks isn't installed");
		String op = a.get("op").getAsString(), key = a.get("k").getAsString();
		return BattleBag.onServer(mc, sp -> switch (op) {
			case "take" -> take(sp, key, a.has("amount") ? a.get("amount").getAsString() : "stack");
			case "store" -> store(sp, key);
			default -> Api.error("unknown backpack op " + op);
		});
	}

	/** Moves one, a stack, or all of an item from the backpacks into the inventory (as much as fits). */
	private static JsonObject take(ServerPlayer sp, String key, String amount) throws Exception {
		List<Backpacks.Slot> from = new ArrayList<>();
		Backpacks.forEachSlot(sp, s -> { if (ItemKeys.key(s.stack()).equals(key)) from.add(s); });
		if (from.isEmpty()) return Api.error("that's not in your backpacks any more");
		int want = switch (amount) {
			case "one" -> 1;
			case "all" -> Integer.MAX_VALUE;
			default -> from.get(0).stack().getMaxStackSize();
		};
		var inv = sp.getInventory();
		int moved = 0;
		for (Backpacks.Slot s : from) {
			if (moved >= want) break;
			ItemStack got = Backpacks.extract(s, Math.min(want - moved, s.stack().getCount()));
			if (got.isEmpty()) continue;
			int n = got.getCount();
			inv.add(got); // shrinks `got` by what fit
			moved += n - got.getCount();
			if (!got.isEmpty()) { Backpacks.insert(s, got); break; } // inventory full: put the rest back
		}
		inv.setChanged();
		if (moved == 0) return Api.error("your inventory is full");
		JsonObject r = Api.ok();
		r.addProperty("moved", moved);
		return r;
	}

	/** Moves every stack of an item from the main inventory (not armour/offhand) into the backpacks. */
	private static JsonObject store(ServerPlayer sp, String key) throws Exception {
		if (key.startsWith("sophisticatedbackpacks:")) return Api.error("backpacks don't go inside backpacks");
		List<Backpacks.Slot> same = new ArrayList<>(), empty = new ArrayList<>();
		Backpacks.forEachSlot(sp, s -> (s.stack().isEmpty() ? empty : ItemKeys.key(s.stack()).equals(key) ? same : new ArrayList<Backpacks.Slot>()).add(s), true);
		if (same.isEmpty() && empty.isEmpty()) return Api.error(Backpacks.LOADED ? "no room in your backpacks (or you're not carrying one)" : "no backpack");
		List<Backpacks.Slot> into = new ArrayList<>(same);
		into.addAll(empty); // top up matching stacks first, then fill empty slots
		var inv = sp.getInventory();
		int moved = 0;
		for (int i = 0; i < inv.items.size(); i++) {
			ItemStack st = inv.items.get(i);
			if (st.isEmpty() || !ItemKeys.key(st).equals(key)) continue;
			ItemStack left = st.copy();
			int before = left.getCount();
			for (Backpacks.Slot s : into) {
				if (left.isEmpty()) break;
				left = Backpacks.insert(s, left);
			}
			moved += before - left.getCount();
			inv.items.set(i, left);
			if (!left.isEmpty()) break; // backpacks are full
		}
		inv.setChanged();
		if (moved == 0) return Api.error("no room in your backpacks");
		JsonObject r = Api.ok();
		r.addProperty("moved", moved);
		return r;
	}
}
