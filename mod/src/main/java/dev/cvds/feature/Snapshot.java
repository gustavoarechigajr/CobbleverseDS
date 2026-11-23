package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the JSON state the touch UI polls, on the client thread, and publishes it with a
 * revision number. HTTP threads only read the published string.
 */
public final class Snapshot {
	private Snapshot() {}

	private static final Object LOCK = new Object();
	private static String body = "{}";
	private static long rev = 0;
	private static volatile long lastPoll = 0;
	private static int tickCounter = 0;
	static final boolean COBBLEMON = FabricLoader.getInstance().isModLoaded("cobblemon");
	/** Tom's Simple Storage. Checked here so Terminal (and Tom's classes) never load without it. */
	public static final boolean TOMS = FabricLoader.getInstance().isModLoaded("toms_storage");

	public static void tick(Minecraft mc) {
		if (++tickCounter % 2 != 0) return;
		if (System.currentTimeMillis() - lastPoll > 10_000) return;
		String next;
		try {
			next = build(mc).toString();
		} catch (Exception e) {
			JsonObject err = new JsonObject();
			err.addProperty("error", String.valueOf(e));
			next = err.toString();
		}
		synchronized (LOCK) {
			if (!next.equals(body)) {
				body = next;
				rev++;
				LOCK.notifyAll();
			}
		}
	}

	/** Returns the state once its revision differs from {@code since}, or after {@code waitMs}. */
	public static String await(long since, long waitMs) throws InterruptedException {
		lastPoll = System.currentTimeMillis();
		long deadline = lastPoll + waitMs;
		synchronized (LOCK) {
			while (rev == since) {
				long left = deadline - System.currentTimeMillis();
				if (left <= 0) break;
				LOCK.wait(left);
			}
			return "{\"rev\":" + rev + (body.length() > 2 ? "," + body.substring(1) : "}");
		}
	}

	static JsonObject build(Minecraft mc) {
		JsonObject o = new JsonObject();
		boolean inGame = mc.player != null && mc.level != null;
		o.addProperty("inGame", inGame);
		if (!inGame) return o;

		JsonObject screen = new JsonObject();
		String kind;
		if (mc.screen == null) kind = "none";
		else if (mc.screen instanceof CreativeModeInventoryScreen) kind = "creative";
		else if (mc.screen instanceof AbstractContainerScreen<?>) kind = "container";
		else kind = "other";
		if (COBBLEMON && "other".equals(kind) && PcScreen.isPc(mc.screen)) kind = "pc";
		screen.addProperty("kind", kind);
		if (TOMS && Terminal.open(mc)) screen.addProperty("terminal", true);
		if (mc.screen != null) {
			screen.addProperty("cls", mc.screen.getClass().getName());
			screen.addProperty("title", mc.screen.getTitle().getString());
		}
		o.add("screen", screen);

		JsonObject items = new JsonObject();
		o.add("menu", menu(mc, items));
		o.add("items", items);
		o.add("status", status(mc));
		if (COBBLEMON) {
			try { o.add("party", Party.json()); } catch (Throwable t) { o.addProperty("partyError", String.valueOf(t)); }
		}
		return o;
	}

	static String group(Slot s) {
		if (s instanceof ResultSlot) return "r";
		if (s.container instanceof Inventory) {
			int i = s.getContainerSlot();
			return i < 9 ? "h" : i < 36 ? "p" : "a";
		}
		if (s.container instanceof CraftingContainer) return "g";
		return "c";
	}

	static JsonObject menu(Minecraft mc, JsonObject items) {
		AbstractContainerMenu m = mc.player.containerMenu;
		JsonObject o = new JsonObject();
		o.addProperty("id", m.containerId);
		o.addProperty("cls", m.getClass().getName());
		if (m instanceof RecipeBookMenu<?, ?> rb) {
			JsonArray grid = new JsonArray();
			grid.add(rb.getGridWidth());
			grid.add(rb.getGridHeight());
			o.add("grid", grid);
		}
		JsonArray slots = new JsonArray();
		for (int i = 0; i < m.slots.size(); i++) {
			Slot s = m.slots.get(i);
			if (!s.isActive() || s.x < -500 || s.y < -500) continue;
			JsonObject so = new JsonObject();
			so.addProperty("i", i);
			so.addProperty("x", s.x);
			so.addProperty("y", s.y);
			so.addProperty("g", group(s));
			ItemStack st = s.getItem();
			if (!st.isEmpty()) {
				so.addProperty("k", describe(st, items));
				so.addProperty("n", st.getCount());
			}
			slots.add(so);
		}
		o.add("slots", slots);
		ItemStack carried = m.getCarried();
		if (!carried.isEmpty()) {
			JsonObject c = new JsonObject();
			c.addProperty("k", describe(carried, items));
			c.addProperty("n", carried.getCount());
			o.add("carried", c);
		}
		return o;
	}

	static String describe(ItemStack st, JsonObject items) {
		String k = ItemKeys.key(st);
		if (!items.has(k)) {
			JsonObject d = new JsonObject();
			d.addProperty("name", st.getHoverName().getString());
			d.addProperty("max", st.getMaxStackSize());
			if (st.isDamageableItem()) d.addProperty("dur", 1.0 - (double) st.getDamageValue() / st.getMaxDamage());
			items.add(k, d);
		}
		return k;
	}

	static JsonObject status(Minecraft mc) {
		JsonObject s = new JsonObject();
		BlockPos p = mc.player.blockPosition();
		s.addProperty("x", p.getX());
		s.addProperty("y", p.getY());
		s.addProperty("z", p.getZ());
		s.addProperty("dim", mc.level.dimension().location().toString());
		mc.level.getBiome(p).unwrapKey().ifPresent(k -> {
			s.addProperty("biome", k.location().toString());
			s.addProperty("biomeName", net.minecraft.client.resources.language.I18n.get("biome." + k.location().getNamespace() + "." + k.location().getPath()));
		});
		long day = mc.level.getDayTime();
		s.addProperty("day", day / 24000L);
		// Rounded to whole in-game minutes so the state isn't "new" every tick.
		s.addProperty("time", Math.floorMod(day, 24000L) / 50 * 50);
		s.addProperty("rain", mc.level.isRaining());
		s.addProperty("thunder", mc.level.isThundering());
		long t = Math.floorMod(day, 24000L);
		s.addProperty("canSleep", mc.level.dimensionType().bedWorks() && ((t >= 12542 && t <= 23459) || mc.level.isThundering()));
		s.addProperty("hp", mc.player.getHealth());
		s.addProperty("maxHp", mc.player.getMaxHealth());
		s.addProperty("food", mc.player.getFoodData().getFoodLevel());
		s.addProperty("xp", mc.player.experienceLevel);
		s.addProperty("hotbar", mc.player.getInventory().selected);
		return s;
	}
}
