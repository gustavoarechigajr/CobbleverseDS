package dev.cvds.feature;

import com.google.gson.JsonObject;
import dev.cvds.inv.Planner;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;

/** Inventory actions from the touch UI. Runs on the client thread. */
public final class InvOps {
	private InvOps() {}

	public static JsonObject run(Minecraft mc, JsonObject a) {
		MenuInv inv = new MenuInv(mc);
		String op = a.get("op").getAsString();
		int expectId = a.has("menu") ? a.get("menu").getAsInt() : inv.menu.containerId;
		if (expectId != inv.menu.containerId) return Api.error("menu changed");
		switch (op) {
			case "move" -> {
				int from = slot(inv, a, "from"), to = slot(inv, a, "to");
				Planner.Amount amt = Planner.Amount.valueOf(a.has("amount") ? a.get("amount").getAsString().toUpperCase(Locale.ROOT) : "ALL");
				Planner.move(inv, from, to, amt);
			}
			case "quick" -> inv.click(slot(inv, a, "slot"), 0, ClickType.QUICK_MOVE);
			case "hotbar" -> {
				int hb = a.get("hotbar").getAsInt();
				if (hb < 0 || hb > 8) return Api.error("hotbar 0-8");
				inv.click(slot(inv, a, "slot"), hb, ClickType.SWAP);
			}
			case "drop" -> inv.click(slot(inv, a, "slot"), a.has("all") && a.get("all").getAsBoolean() ? 1 : 0, ClickType.THROW);
			case "sort" -> {
				List<Integer> group = groupSlots(inv, a.get("group").getAsString(), null);
				if (!Planner.sort(inv, group, Comparator.comparing(k -> sortName(inv, k))))
					return Api.error("could not sort this container (stacks too large to move, or the server disagreed)");
			}
			case "quickAll" -> {
				String key = a.has("key") ? a.get("key").getAsString() : null;
				for (int s : groupSlots(inv, a.get("group").getAsString(), key)) inv.click(s, 0, ClickType.QUICK_MOVE);
			}
			case "clearCursor" -> clearCursor(inv);
			default -> { return Api.error("unknown op " + op); }
		}
		if (inv.carrying()) clearCursor(inv);
		JsonObject r = Api.ok();
		r.addProperty("carrying", inv.carrying());
		return r;
	}

	static int slot(MenuInv inv, JsonObject a, String field) {
		int s = a.get(field).getAsInt();
		if (!inv.valid(s)) throw new IllegalArgumentException("bad slot " + s);
		return s;
	}

	/** Slots of a UI group: "c" container, "p" main inventory, "h" hotbar, "pc" = p+h; optionally only one item key. */
	static List<Integer> groupSlots(MenuInv inv, String group, String key) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < inv.menu.slots.size(); i++) {
			var s = inv.menu.slots.get(i);
			if (!s.isActive()) continue;
			String g = Snapshot.group(s);
			boolean match = group.equals(g) || (group.equals("pc") && (g.equals("p") || g.equals("h")));
			if (!match) continue;
			if (key != null && !inv.key(i).equals(key)) continue;
			out.add(i);
		}
		return out;
	}

	static String sortName(MenuInv inv, String key) {
		var sample = ItemKeys.SAMPLES.get(key);
		return sample == null ? key : sample.getHoverName().getString().toLowerCase(Locale.ROOT);
	}

	/** Puts whatever is on the cursor into the first slot of the player's inventory that takes it. */
	static void clearCursor(MenuInv inv) {
		for (String g : new String[]{"p", "h", "c"}) {
			for (int s : groupSlots(inv, g, null)) {
				if (!inv.carrying()) return;
				if (inv.empty(s) || (inv.key(s).equals(inv.carriedKey()) && inv.count(s) < inv.max(s))) inv.pickup(s, 0);
			}
		}
	}
}
