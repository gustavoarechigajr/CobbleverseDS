package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cvds.inv.Planner;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

/** Recipe search and one-tap crafting for any open menu with a crafting grid. */
public final class Crafting {
	private Crafting() {}

	static RecipeBookMenu<?, ?> craftingMenu(Minecraft mc) {
		return mc.player.containerMenu instanceof RecipeBookMenu<?, ?> rb && rb.getRecipeBookType() == RecipeBookType.CRAFTING ? rb : null;
	}

	public static JsonObject search(Minecraft mc, String query, int limit) {
		JsonObject res = new JsonObject();
		RecipeBookMenu<?, ?> rb = craftingMenu(mc);
		if (rb == null) { res.addProperty("error", "no crafting grid open"); return res; }
		res.addProperty("menu", mc.player.containerMenu.containerId);
		String q = query.toLowerCase(Locale.ROOT).strip();
		StackedContents have = new StackedContents();
		mc.player.getInventory().fillStackedContents(have);
		rb.fillCraftSlotsStackedContents(have);
		var items = new JsonObject();
		List<JsonObject> craftable = new ArrayList<>(), other = new ArrayList<>();
		Set<String> seenOutputs = new HashSet<>();
		for (RecipeHolder<CraftingRecipe> h : mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
			CraftingRecipe r = h.value();
			if (r.isSpecial() || !r.canCraftInDimensions(rb.getGridWidth(), rb.getGridHeight())) continue;
			ItemStack out = r.getResultItem(mc.level.registryAccess());
			if (out.isEmpty()) continue;
			String name = out.getHoverName().getString();
			if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !h.id().toString().contains(q)) continue;
			boolean can = have.canCraft(r, null);
			if (!can && q.isEmpty()) continue; // unfiltered list shows only what you can make now
			JsonObject o = new JsonObject();
			o.addProperty("id", h.id().toString());
			o.addProperty("k", Snapshot.describe(out, items));
			o.addProperty("n", out.getCount());
			o.addProperty("name", name);
			o.addProperty("can", can);
			o.addProperty("book", mc.player.getRecipeBook().contains(h));
			String dedupe = o.get("k").getAsString() + can;
			if (!seenOutputs.add(dedupe + h.id())) continue;
			(can ? craftable : other).add(o);
		}
		Comparator<JsonObject> byName = Comparator.comparing(o -> o.get("name").getAsString());
		craftable.sort(byName);
		other.sort(byName);
		JsonArray list = new JsonArray();
		for (var o : craftable) { if (list.size() >= limit) break; list.add(o); }
		for (var o : other) { if (list.size() >= limit) break; list.add(o); }
		res.add("recipes", list);
		res.add("items", items);
		return res;
	}

	/** Crafts the recipe once (or as many times as possible) and quick-moves the result into the inventory. */
	public static JsonObject craft(Minecraft mc, String id, boolean max) {
		RecipeBookMenu<?, ?> rb = craftingMenu(mc);
		if (rb == null) return Api.error("no crafting grid open");
		var holder = mc.level.getRecipeManager().byKey(ResourceLocation.parse(id)).orElse(null);
		if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) return Api.error("unknown recipe " + id);
		MenuInv inv = new MenuInv(mc);
		int result = rb.getResultSlotIndex();
		clearGrid(inv);
		if (mc.player.getRecipeBook().contains(holder)) {
			// Server-side placement, same packet as the vanilla recipe book. The result click that
			// follows is processed after it on the server, so it crafts what was placed.
			mc.gameMode.handlePlaceRecipe(inv.menu.containerId, holder, max);
		} else {
			int sets = max ? 64 : 1;
			int placed = 0;
			while (placed < sets && placeOnce(inv, rb, recipe)) placed++;
			if (placed == 0) return Api.error("missing ingredients");
		}
		inv.click(result, 0, ClickType.QUICK_MOVE);
		if (inv.carrying()) InvOps.clearCursor(inv);
		return Api.ok();
	}

	static List<Integer> gridSlots(MenuInv inv) {
		List<Integer> grid = new ArrayList<>();
		for (int i = 0; i < inv.menu.slots.size(); i++) if (Snapshot.group(inv.menu.slots.get(i)).equals("g")) grid.add(i);
		return grid;
	}

	static void clearGrid(MenuInv inv) {
		for (int s : gridSlots(inv)) if (!inv.empty(s)) inv.click(s, 0, ClickType.QUICK_MOVE);
	}

	/** Puts one set of ingredients into the grid using normal clicks. False if something is missing. */
	static boolean placeOnce(MenuInv inv, RecipeBookMenu<?, ?> rb, CraftingRecipe r) {
		List<Integer> grid = gridSlots(inv);
		int gw = rb.getGridWidth();
		NonNullList<Ingredient> ings = r.getIngredients();
		int w = r instanceof ShapedRecipe sr ? sr.getWidth() : gw;
		List<int[]> plan = new ArrayList<>(); // [gridSlot, ingredientIndex]
		for (int i = 0; i < ings.size(); i++) {
			if (ings.get(i).isEmpty()) continue;
			int gx = i % w, gy = i / w;
			int idx = gy * gw + gx;
			if (idx >= grid.size()) return false;
			plan.add(new int[]{grid.get(idx), i});
		}
		// Check availability first so a partial set is never left in the grid.
		Map<Integer, Integer> used = new HashMap<>();
		List<int[]> moves = new ArrayList<>();
		for (int[] p : plan) {
			int src = -1;
			for (int s : InvOps.groupSlots(inv, "pc", null)) {
				int left = inv.count(s) - used.getOrDefault(s, 0);
				if (left > 0 && ings.get(p[1]).test(inv.stack(s))) { src = s; break; }
			}
			if (src < 0) return false;
			used.merge(src, 1, Integer::sum);
			moves.add(new int[]{src, p[0]});
		}
		for (int[] m : moves) Planner.move(inv, m[0], m[1], Planner.Amount.ONE);
		return true;
	}
}
