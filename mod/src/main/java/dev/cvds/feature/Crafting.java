package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

	public static JsonObject search(Minecraft mc, String query, int limit) { return search(mc, query, limit, Set.of()); }

	/** {@code favs}: recipe ids that are always listed (first), craftable or not. */
	public static JsonObject search(Minecraft mc, String query, int limit, Set<String> favs) {
		JsonObject res = new JsonObject();
		RecipeBookMenu<?, ?> rb = craftingMenu(mc);
		if (rb == null) { res.addProperty("error", "no crafting grid open"); return res; }
		res.addProperty("menu", mc.player.containerMenu.containerId);
		String q = query.toLowerCase(Locale.ROOT).strip();
		StackedContents have = new StackedContents();
		mc.player.getInventory().fillStackedContents(have);
		rb.fillCraftSlotsStackedContents(have);
		var items = new JsonObject();
		List<JsonObject> craftable = new ArrayList<>(), other = new ArrayList<>(), favList = new ArrayList<>();
		Set<String> seenOutputs = new HashSet<>();
		for (RecipeHolder<CraftingRecipe> h : mc.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
			CraftingRecipe r = h.value();
			if (r.isSpecial() || !r.canCraftInDimensions(rb.getGridWidth(), rb.getGridHeight())) continue;
			ItemStack out = r.getResultItem(mc.level.registryAccess());
			if (out.isEmpty()) continue;
			String name = out.getHoverName().getString();
			boolean fav = favs.contains(h.id().toString());
			if (!fav && !q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !h.id().toString().contains(q)) continue;
			boolean can = have.canCraft(r, null);
			if (!can && q.isEmpty() && !fav) continue; // unfiltered list shows only what you can make now
			JsonObject o = new JsonObject();
			o.addProperty("id", h.id().toString());
			o.addProperty("k", Snapshot.describe(out, items));
			o.addProperty("n", out.getCount());
			o.addProperty("name", name);
			o.addProperty("can", can);
			o.addProperty("book", mc.player.getRecipeBook().contains(h));
			Map<String, Integer> need = new LinkedHashMap<>();
			for (Ingredient ing : r.getIngredients()) {
				ItemStack[] opts = ing.getItems();
				if (opts.length > 0) need.merge(opts[0].getHoverName().getString() + (opts.length > 1 ? " (any)" : ""), 1, Integer::sum);
			}
			JsonArray ingsJson = new JsonArray();
			need.forEach((n, c) -> ingsJson.add(c + "× " + n));
			o.add("ings", ingsJson);
			o.addProperty("fav", fav);
			// Ingredients with icons and have/need counts (inventory + grid).
			Map<String, int[]> counts = new LinkedHashMap<>(); // key -> {need, have}
			Map<String, Ingredient> byKey = new LinkedHashMap<>();
			for (Ingredient ing : r.getIngredients()) {
				ItemStack[] opts = ing.getItems();
				if (opts.length == 0) continue;
				String key = Snapshot.describe(opts[0], items);
				counts.computeIfAbsent(key, k -> new int[2])[0]++;
				byKey.putIfAbsent(key, ing);
			}
			JsonArray ingr = new JsonArray();
			for (var e : counts.entrySet()) {
				Ingredient ing = byKey.get(e.getKey());
				int got = 0;
				for (ItemStack s : mc.player.getInventory().items) if (!s.isEmpty() && ing.test(s)) got += s.getCount();
				for (int g : gridSlotsOf(mc)) { ItemStack s = mc.player.containerMenu.slots.get(g).getItem(); if (!s.isEmpty() && ing.test(s)) got += s.getCount(); }
				JsonObject x = new JsonObject();
				x.addProperty("k", e.getKey());
				x.addProperty("need", e.getValue()[0]);
				x.addProperty("have", got);
				x.addProperty("any", ing.getItems().length > 1);
				ingr.add(x);
			}
			o.add("ingr", ingr);
			String dedupe = o.get("k").getAsString() + can;
			if (!seenOutputs.add(dedupe + h.id())) continue;
			(fav ? favList : can ? craftable : other).add(o);
		}
		Comparator<JsonObject> byName = Comparator.comparing(o -> o.get("name").getAsString());
		craftable.sort(byName);
		other.sort(byName);
		favList.sort(byName);
		JsonArray list = new JsonArray();
		for (var o : favList) list.add(o);
		for (var o : craftable) { if (list.size() >= limit) break; list.add(o); }
		for (var o : other) { if (list.size() >= limit) break; list.add(o); }
		res.add("recipes", list);
		res.add("items", items);
		return res;
	}

	/** Crafts the recipe once (or as many times as possible) and quick-moves the result into the inventory. */
	public static JsonObject craft(Minecraft mc, String id, boolean max) { return craft(mc, id, max, 1); }

	/** Crafts {@code count} sets (capped at what fits and what you have), or as many as possible with {@code max}. */
	public static JsonObject craft(Minecraft mc, String id, boolean max, int count) {
		RecipeBookMenu<?, ?> rb = craftingMenu(mc);
		if (rb == null) return Api.error("no crafting grid open");
		var holder = mc.level.getRecipeManager().byKey(ResourceLocation.parse(id)).orElse(null);
		if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) return Api.error("unknown recipe " + id);
		MenuInv inv = new MenuInv(mc);
		if (Snapshot.TOMS && Terminal.craftingTerminal(mc)) {
			// Tom's crafting terminal fills the grid from the whole storage network on the server.
			// (it fills one set or as many as possible: a count above 1 crafts the maximum)
			return Terminal.craft(mc, id, max || count > 1, ItemKeys.key(recipe.getResultItem(mc.level.registryAccess())));
		}
		clearGrid(inv);
		for (int g : gridSlots(inv)) if (!inv.empty(g)) return Api.error("inventory full: can't clear the crafting grid");
		// Ingredients are placed with ordinary clicks rather than the recipe-book packet: servers
		// ignore that packet for recipes the player hasn't unlocked, and Cobbleverse never unlocks them.
		List<int[]> plan = plan(inv, rb, recipe);
		if (plan == null) return Api.error("this recipe doesn't fit the grid");
		int sets = max ? maxSets(inv, recipe, plan) : count <= 1 ? (feasible(inv, recipe, plan, 1) ? 1 : 0) : Math.min(count, maxSets(inv, recipe, plan));
		if (sets == 0) return Api.error("missing ingredients");
		if (!place(inv, recipe, plan, sets)) {
			clearGrid(inv);
			return Api.error("could not place ingredients");
		}
		inv.click(rb.getResultSlotIndex(), 0, ClickType.QUICK_MOVE);
		if (inv.carrying()) InvOps.clearCursor(inv);
		JsonObject r = Api.ok();
		r.addProperty("sets", sets);
		return r;
	}

	static List<Integer> gridSlotsOf(Minecraft mc) {
		List<Integer> grid = new ArrayList<>();
		var slots = mc.player.containerMenu.slots;
		for (int i = 0; i < slots.size(); i++) if (Snapshot.group(slots.get(i)).equals("g")) grid.add(i);
		return grid;
	}

	static List<Integer> gridSlots(MenuInv inv) {
		List<Integer> grid = new ArrayList<>();
		for (int i = 0; i < inv.menu.slots.size(); i++) if (Snapshot.group(inv.menu.slots.get(i)).equals("g")) grid.add(i);
		return grid;
	}

	static void clearGrid(MenuInv inv) {
		for (int s : gridSlots(inv)) if (!inv.empty(s)) inv.click(s, 0, ClickType.QUICK_MOVE);
	}

	/** [gridSlot, ingredientIndex] for every non-empty ingredient, or null if it doesn't fit. */
	static List<int[]> plan(MenuInv inv, RecipeBookMenu<?, ?> rb, CraftingRecipe r) {
		List<Integer> grid = gridSlots(inv);
		int gw = rb.getGridWidth();
		NonNullList<Ingredient> ings = r.getIngredients();
		int w = r instanceof ShapedRecipe sr ? sr.getWidth() : gw;
		List<int[]> plan = new ArrayList<>();
		for (int i = 0; i < ings.size(); i++) {
			if (ings.get(i).isEmpty()) continue;
			int idx = (i / w) * gw + (i % w);
			if (i % w >= gw || idx >= grid.size()) return null;
			plan.add(new int[]{grid.get(idx), i});
		}
		return plan;
	}

	/**
	 * Picks inventory sources for {@code sets} copies of every ingredient, or null if short.
	 * Each entry: [gridSlot, sourceSlot, count].
	 */
	static List<int[]> allocate(MenuInv inv, CraftingRecipe r, List<int[]> plan, int sets) {
		Map<Integer, Integer> used = new HashMap<>();
		List<int[]> moves = new ArrayList<>();
		for (int[] p : plan) {
			Ingredient ing = r.getIngredients().get(p[1]);
			int need = sets;
			for (int s : InvOps.groupSlots(inv, "pc", null)) {
				if (need == 0) break;
				ItemStack st = inv.stack(s);
				int left = st.getCount() - used.getOrDefault(s, 0);
				if (left <= 0 || !ing.test(st)) continue;
				if (sets > st.getMaxStackSize()) return null; // the grid slot couldn't hold that many
				int take = Math.min(left, need);
				used.merge(s, take, Integer::sum);
				moves.add(new int[]{p[0], s, take});
				need -= take;
			}
			if (need > 0) return null;
		}
		return moves;
	}

	static boolean feasible(MenuInv inv, CraftingRecipe r, List<int[]> plan, int sets) {
		return allocate(inv, r, plan, sets) != null;
	}

	static int maxSets(MenuInv inv, CraftingRecipe r, List<int[]> plan) {
		int lo = 0, hi = 64;
		while (lo < hi) { // largest feasible count; feasibility is monotonic
			int mid = (lo + hi + 1) / 2;
			if (feasible(inv, r, plan, mid)) lo = mid; else hi = mid - 1;
		}
		return lo;
	}

	/** Moves the allocated ingredients into the grid with as few clicks as possible. */
	static boolean place(MenuInv inv, CraftingRecipe r, List<int[]> plan, int sets) {
		List<int[]> moves = allocate(inv, r, plan, sets);
		if (moves == null) return false;
		for (int[] m : moves) {
			int grid = m[0], src = m[1], count = m[2];
			if (inv.count(src) <= count) {
				inv.pickup(src, 0);
				inv.pickup(grid, 0);
			} else {
				inv.pickup(src, 0);
				for (int i = 0; i < count; i++) inv.pickup(grid, 1);
			}
			if (inv.carrying()) inv.pickup(src, 0);
			if (inv.carrying()) return false;
		}
		return true;
	}
}
