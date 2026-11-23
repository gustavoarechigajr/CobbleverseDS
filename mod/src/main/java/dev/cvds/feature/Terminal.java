package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tom.storagemod.inventory.StoredItemStack;
import com.tom.storagemod.menu.StorageTerminalMenu;
import com.tom.storagemod.util.TerminalSyncManager.SlotAction;
import net.minecraft.client.Minecraft;

/**
 * Tom's Simple Storage terminals (storage + crafting terminal). The network's contents are
 * synced to the client by the terminal itself; pulling goes through its own interact packet.
 * Storing needs nothing special: shift-clicking a player slot inserts into the network.
 */
final class Terminal {
	private Terminal() {}
	static StorageTerminalMenu menu(Minecraft mc) {
		return mc.player != null && mc.player.containerMenu instanceof StorageTerminalMenu m ? m : null;
	}

	static boolean open(Minecraft mc) { return menu(mc) != null; }

	static boolean craftingTerminal(Minecraft mc) {
		return mc.player != null && mc.player.containerMenu instanceof com.tom.storagemod.menu.CraftingTerminalMenu;
	}

	static JsonObject json(Minecraft mc) {
		JsonObject o = new JsonObject();
		StorageTerminalMenu m = menu(mc);
		o.addProperty("open", m != null);
		if (m == null) return o;
		o.addProperty("menu", m.containerId);
		JsonObject items = new JsonObject();
		JsonArray list = new JsonArray();
		for (StoredItemStack s : m.sync.getAsList()) {
			if (s == null || s.getQuantity() <= 0) continue;
			JsonObject e = new JsonObject();
			e.addProperty("k", Snapshot.describe(s.getStack(), items));
			e.addProperty("n", s.getQuantity());
			list.add(e);
		}
		o.add("list", list);
		o.add("items", items);
		o.addProperty("free", m.freeCount);
		o.addProperty("slots", m.slotCount);
		return o;
	}

	/** Pulls a stack (or one item) of the given item key into the player's inventory. */
	static JsonObject take(Minecraft mc, String key, boolean one) {
		StorageTerminalMenu m = menu(mc);
		if (m == null) return Api.error("no storage terminal open");
		for (StoredItemStack s : m.sync.getAsList()) {
			if (s == null || !ItemKeys.key(s.getStack()).equals(key)) continue;
			if (one) {
				armOffhandFix(mc, key);
				m.sync.sendInteract(s, SlotAction.PULL_ONE, true);
			}
			else m.sync.sendInteract(s, SlotAction.SHIFT_PULL, false);
			return Api.ok();
		}
		return Api.error("that item isn't in the network any more");
	}

	// Tom's "pull one into inventory" fills from the end of the menu, which is the offhand slot.
	// When the offhand was empty, move what lands there into the main inventory once it syncs.
	private static String fixKey;
	private static int fixTicks;

	private static void armOffhandFix(Minecraft mc, String key) {
		MenuInv inv = new MenuInv(mc);
		int off = offhandSlot(inv);
		if (off < 0 || !inv.empty(off)) return;
		fixKey = key;
		fixTicks = 20;
	}

	static int offhandSlot(MenuInv inv) {
		for (int i = inv.menu.slots.size() - 1; i >= 0; i--) {
			var sl = inv.menu.slots.get(i);
			if (sl.container instanceof net.minecraft.world.entity.player.Inventory && sl.getContainerSlot() == 40) return i;
		}
		return -1;
	}

	static void tick(Minecraft mc) {
		tickCraft(mc);
		if (fixKey == null) return;
		if (--fixTicks < 0 || menu(mc) == null) { fixKey = null; return; }
		MenuInv inv = new MenuInv(mc);
		int off = offhandSlot(inv);
		if (off < 0 || inv.empty(off)) return;
		if (!inv.key(off).equals(fixKey) || inv.carrying()) { fixKey = null; return; }
		int target = -1;
		for (int s : InvOps.groupSlots(inv, "pc", fixKey)) if (inv.count(s) < inv.max(s)) { target = s; break; }
		if (target < 0) for (int s : InvOps.groupSlots(inv, "pc", null)) if (inv.empty(s)) { target = s; break; }
		if (target >= 0) dev.cvds.inv.Planner.move(inv, off, target, dev.cvds.inv.Planner.Amount.ALL);
		fixKey = null;
	}

	// ---- crafting terminal: fill the grid from the network, then craft once the result syncs ----
	private static int craftTicks;
	private static boolean craftMax;
	private static String craftKey;

	static JsonObject craft(Minecraft mc, String recipeId, boolean max, String resultKey) {
		if (!(mc.player.containerMenu instanceof com.tom.storagemod.menu.CraftingTerminalMenu m)) return Api.error("no crafting terminal open");
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		tag.putString("fill", recipeId); // same message Tom's JEI/EMI transfer sends
		m.sendMessage(tag);
		craftMax = max;
		craftKey = resultKey;
		craftTicks = 40;
		JsonObject r = Api.ok();
		r.addProperty("network", true);
		return r;
	}

	static void tickCraft(Minecraft mc) {
		if (craftTicks <= 0) return;
		if (!(mc.player != null && mc.player.containerMenu instanceof com.tom.storagemod.menu.CraftingTerminalMenu m)) { craftTicks = 0; return; }
		craftTicks--;
		MenuInv inv = new MenuInv(mc);
		int result = m.getResultSlotIndex();
		// Wait until the grid shows THIS recipe's result: the terminal can still be showing the
		// previous one (it keeps the grid filled) until the server answers the fill request.
		if (inv.empty(result) || !inv.key(result).equals(craftKey)) return;
		craftTicks = 0;
		if (craftMax) {
			inv.click(result, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE);
		} else {
			inv.pickup(result, 0);
			InvOps.clearCursor(inv);
		}
	}
}
