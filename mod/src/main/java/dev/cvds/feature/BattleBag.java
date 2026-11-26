package dev.cvds.feature;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.item.PokemonAndMoveSelectingItem;
import com.cobblemon.mod.common.api.item.PokemonSelectingItem;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BagItemActionResponse;
import com.cobblemon.mod.common.battles.BagItems;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.PokeBallItem;
import com.cobblemon.mod.common.item.battle.BagItem;
import com.cobblemon.mod.common.item.battle.BagItemLike;
import com.cobblemon.mod.common.item.interactive.PotionItem;
import com.cobblemon.mod.common.item.interactive.ReviveItem;
import com.cobblemon.mod.common.item.interactive.StatusCureItem;
import com.cobblemon.mod.common.pokeball.PokeBall;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The battle Bag: medicine and battle items from the inventory and from carried backpacks, used
 * the way Cobblemon's own item code does, plus Poké Balls to equip or throw at a wild Pokémon.
 * Singleplayer only (it works on the integrated server). Called from HTTP threads.
 */
final class BattleBag {
	private BattleBag() {}

	/** Where a stack was found: an inventory slot or a backpack slot. */
	private record Found(ItemStack stack, int invSlot, Backpacks.Slot pack) {
		boolean inPack() { return pack != null; }
	}

	interface ServerCall { JsonObject call(ServerPlayer sp) throws Exception; }

	static JsonObject onServer(Minecraft mc, ServerCall c) throws Exception {
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return Api.error("the bag needs a singleplayer world");
		UUID me = mc.player.getUUID();
		return server.submit(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(me);
			if (sp == null) return Api.error("player not found");
			try { return c.call(sp); }
			catch (Exception e) { return Api.error(e.getClass().getSimpleName() + ": " + e.getMessage()); }
		}).get(5, TimeUnit.SECONDS);
	}

	// ---------- finding items ----------

	private static List<Found> scan(ServerPlayer sp) {
		List<Found> out = new ArrayList<>();
		var inv = sp.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) out.add(new Found(s, i, null));
		}
		Backpacks.forEachSlot(sp, slot -> out.add(new Found(slot.stack(), -1, slot)));
		return out;
	}

	static BagItem bagItemFor(ItemStack s) {
		if (s.getItem() instanceof PokemonSelectingItem p) return p.getBagItem();
		if (s.getItem() instanceof ReviveItem r) return r.getBagItem();
		BagItemLike like = BagItems.getConvertibleForStack(s);
		return like != null ? like.getBagItem(s) : null;
	}

	/** The Pokémon this item can be used on right now (empty = not usable). */
	private static List<BattlePokemon> targets(ItemStack s, PokemonBattle battle, BattleActor actor) {
		List<BattlePokemon> out = new ArrayList<>();
		BagItem bag = bagItemFor(s);
		if (bag == null) return out;
		for (BattlePokemon bp : actor.getPokemonList()) {
			try {
				boolean ok;
				if (s.getItem() instanceof PokemonSelectingItem p) ok = p.canUseOnBattlePokemon(s, bp) && bag.canUse(s, battle, bp);
				else if (s.getItem() instanceof ReviveItem) ok = bp.getHealth() <= 0 && bag.canUse(s, battle, bp);
				else ok = bag.canUse(s, battle, bp);
				if (ok) out.add(bp);
			} catch (Throwable ignored) {}
		}
		return out;
	}

	// ---------- listing ----------

	static JsonObject list(Minecraft mc) throws Exception {
		return onServer(mc, sp -> {
			JsonObject o = new JsonObject();
			PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(sp);
			BattleActor actor = battle != null ? battle.getActor(sp) : null;
			o.addProperty("inBattle", actor != null);
			o.addProperty("wild", battle != null && wildTarget(battle) != null);
			o.addProperty("canAct", actor != null && actor.canFitForcedAction());
			o.addProperty("backpacks", Backpacks.LOADED);
			PokemonEntity wild = battle != null ? wildTarget(battle) : null;
			// Group identical items: one entry per item key with counts per source.
			Map<String, JsonObject> items = new LinkedHashMap<>(), balls = new LinkedHashMap<>();
			for (Found f : scan(sp)) {
				ItemStack s = f.stack();
				boolean ball = s.getItem() instanceof PokeBallItem;
				BagItem bag = ball ? null : bagItemFor(s);
				boolean needsMove = s.getItem() instanceof PokemonAndMoveSelectingItem;
				if (!ball && bag == null && !needsMove) continue;
				String key = ItemKeys.key(s);
				JsonObject e = (ball ? balls : items).computeIfAbsent(key, k -> {
					JsonObject n = new JsonObject();
					n.addProperty("k", k);
					n.addProperty("name", s.getHoverName().getString());
					n.addProperty("inv", 0);
					n.addProperty("pack", 0);
					if (ball && wild != null) {
						int c = catchChance(sp, ((PokeBallItem) s.getItem()).getPokeBall(), wild);
						if (c >= 0) n.addProperty("chance", c);
					}
					if (!ball) {
						n.addProperty("inGame", needsMove); // PP items need a move choice: use them in game
						JsonArray ts = new JsonArray();
						if (actor != null && !needsMove)
							for (BattlePokemon bp : targets(s, battle, actor)) {
								JsonObject t = new JsonObject();
								t.addProperty("id", bp.getUuid().toString());
								t.addProperty("name", bp.getEffectedPokemon().getDisplayName(false).getString());
								t.addProperty("hp", bp.getHealth());
								t.addProperty("maxHp", bp.getMaxHealth());
								ts.add(t);
							}
						n.add("targets", ts);
					}
					return n;
				});
				String src = f.inPack() ? "pack" : "inv";
				e.addProperty(src, e.get(src).getAsInt() + s.getCount());
			}
			JsonArray ia = new JsonArray(), ba = new JsonArray();
			items.values().forEach(ia::add);
			balls.values().forEach(ba::add);
			o.add("items", ia);
			o.add("balls", ba);
			o.addProperty("ok", true);
			return o;
		});
	}

	// ---------- taking one item out of where it is ----------

	private static Found find(ServerPlayer sp, String key) {
		Found pack = null;
		for (Found f : scan(sp)) {
			if (!ItemKeys.key(f.stack()).equals(key)) continue;
			if (!f.inPack()) return f; // prefer the inventory
			if (pack == null) pack = f;
		}
		return pack;
	}

	/** Gives an untouched item back: to its backpack slot, else the inventory, else the ground. */
	private static void giveBack(ServerPlayer sp, Found f, ItemStack s) throws Exception {
		if (s.isEmpty()) return;
		if (f.inPack()) s = Backpacks.insert(f.pack(), s);
		if (!s.isEmpty() && !sp.getInventory().add(s)) sp.drop(s, false);
	}

	// ---------- actions ----------

	static JsonObject act(Minecraft mc, JsonObject a) throws Exception {
		String op = a.get("op").getAsString();
		String key = a.has("k") ? a.get("k").getAsString() : "";
		JsonObject r = onServer(mc, sp -> switch (op) {
			case "use" -> use(sp, key, a.get("target").getAsString());
			case "equip" -> equip(sp, key);
			case "throw" -> throwBall(sp, key);
			case "heal" -> healParty(sp);
			default -> Api.error("unknown bag op " + op);
		});
		// Turn the camera to where the ball went, so the throw reads on screen.
		if (r.has("yaw") && mc.player != null) {
			float yaw = r.get("yaw").getAsFloat(), pitch = r.get("pitch").getAsFloat();
			mc.execute(() -> { if (mc.player != null) { mc.player.setYRot(yaw); mc.player.setXRot(pitch); } });
		}
		return r;
	}

	private static JsonObject use(ServerPlayer sp, String key, String targetId) throws Exception {
		PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(sp);
		BattleActor actor = battle != null ? battle.getActor(sp) : null;
		if (actor == null) return Api.error("not in a battle");
		if (!actor.canFitForcedAction()) return Api.error("you can't use an item right now");
		if (battle.getFormat().getRuleSet().contains("Bag Clause")) return Api.error("items aren't allowed in this battle");
		Found f = find(sp, key);
		if (f == null) return Api.error("you don't have that item any more");
		BattlePokemon target = null;
		for (BattlePokemon bp : targets(f.stack(), battle, actor)) if (bp.getUuid().toString().equals(targetId)) target = bp;
		if (target == null) return Api.error("that item won't work on that Pokémon now");
		// Inventory items are used in place; a backpack item is taken out first and returned if unused.
		ItemStack stack = f.inPack() ? Backpacks.extract(f.pack(), 1) : f.stack();
		if (stack.isEmpty()) return Api.error("couldn't take it out of the backpack");
		int before = stack.getCount();
		if (stack.getItem() instanceof PokemonSelectingItem p) {
			p.applyToBattlePokemon(sp, stack, target);
		} else if (stack.getItem() instanceof ReviveItem r) {
			// Cobblemon's Revive only works from the hand; this is the same action its use() sends.
			actor.forceChoose(new BagItemActionResponse(r.getBagItem(), target, target.getUuid().toString()));
			if (!sp.isCreative()) stack.shrink(1);
		} else {
			BagItemLike like = BagItems.getConvertibleForStack(stack);
			if (like == null || !like.handleInteraction(sp, target, stack)) {
				if (f.inPack()) giveBack(sp, f, stack);
				return Api.error("the item couldn't be used");
			}
		}
		boolean used = stack.getCount() < before || sp.isCreative();
		if (f.inPack() && !stack.isEmpty()) giveBack(sp, f, stack);
		return used ? Api.ok() : Api.error("the item couldn't be used");
	}

	/** Puts the ball stack in the main hand; what was there goes where the ball came from. */
	private static JsonObject equip(ServerPlayer sp, String key) throws Exception {
		Found f = find(sp, key);
		if (f == null || !(f.stack().getItem() instanceof PokeBallItem)) return Api.error("you don't have that ball any more");
		var inv = sp.getInventory();
		int sel = inv.selected;
		if (!f.inPack()) {
			if (f.invSlot() == sel) return Api.ok();
			ItemStack hand = inv.getItem(sel);
			inv.setItem(sel, inv.getItem(f.invSlot()));
			inv.setItem(f.invSlot(), hand);
		} else {
			ItemStack balls = Backpacks.extract(f.pack(), f.stack().getCount());
			ItemStack hand = inv.getItem(sel);
			inv.setItem(sel, balls);
			if (!hand.isEmpty()) giveBack(sp, f, hand);
		}
		inv.setChanged();
		return Api.ok();
	}

	// ---------- catch chance ----------

	static final int CATCH_TRIALS = 1200; // ±2 points; cached below so the number holds still between turns
	private static final Map<String, Integer> CATCH_CACHE = new HashMap<>();

	/**
	 * Chance (0-100) that one throw of this ball catches the wild Pokémon right now: Cobblemon's own capture
	 * calculator (whatever the pack configures) run on a throwaway ball that is never added to the world, so
	 * HP, status, level, ball bonuses and critical captures all count exactly as they will. -1 if it can't tell.
	 */
	static int catchChance(ServerPlayer sp, PokeBall ball, PokemonEntity target) {
		try {
			if (ball.getCatchRateModifier().isGuaranteed()) return 100;
			var p = target.getPokemon();
			String key = ball.getName() + "|" + target.getUUID() + "|" + p.getCurrentHealth() + "|" + (p.getStatus() == null ? "" : p.getStatus().getStatus().getName());
			synchronized (CATCH_CACHE) {
				Integer c = CATCH_CACHE.get(key);
				if (c != null) return c;
				if (CATCH_CACHE.size() > 64) CATCH_CACHE.clear();
			}
			var calc = Cobblemon.INSTANCE.getConfig().getCaptureCalculator();
			var probe = new EmptyPokeBallEntity(ball, sp.level(), sp, CobblemonEntities.EMPTY_POKEBALL);
			int caught = 0;
			for (int i = 0; i < CATCH_TRIALS; i++) if (calc.processCapture(sp, probe, target).isSuccessfulCapture()) caught++;
			int pct = Math.round(caught * 100f / CATCH_TRIALS);
			synchronized (CATCH_CACHE) { CATCH_CACHE.put(key, pct); }
			return pct;
		} catch (Throwable e) {
			return -1;
		}
	}

	// ---------- heal the party from the bag (outside battle) ----------

	/** Default heal amounts of Potion, Super, Hyper, Max Potion, Full Restore (the last two heal fully). */
	private static final int[] POTION_HEAL = {20, 60, 120, Integer.MAX_VALUE, Integer.MAX_VALUE};

	/**
	 * Revives, heals and cures every party Pokémon that needs it with the cheapest item that does the job,
	 * from the inventory and backpacks: Revive before Max Revive, the smallest potion that covers the missing
	 * HP (else the biggest there is), a matching status cure before Full Heal.
	 */
	private static JsonObject healParty(ServerPlayer sp) throws Exception {
		if (BattleRegistry.getBattleByParticipatingPlayer(sp) != null) return Api.error("finish the battle first (use the Bag there)");
		JsonArray used = new JsonArray(), missingItems = new JsonArray();
		for (Pokemon p : Cobblemon.INSTANCE.getStorage().getParty(sp)) {
			String name = p.getDisplayName(false).getString();
			if (p.isFainted()) {
				Found f = best(sp, s -> s.getItem() instanceof ReviveItem r ? (r.getMax() ? 1 : 0) : -1);
				if (f == null) { missingItems.add(name + " needs a Revive"); continue; }
				String item = itemName(f);
				useOne(sp, f, stack -> {
					boolean max = ((ReviveItem) stack.getItem()).getMax();
					p.setCurrentHealth(max ? p.getMaxHealth() : (int) Math.ceil(p.getMaxHealth() / 2f));
					if (!sp.isCreative()) stack.shrink(1);
				});
				used.add(item + " → " + name);
			}
			for (int guard = 0; guard < 4 && !p.isFainted() && p.getCurrentHealth() < p.getMaxHealth(); guard++) {
				int missing = p.getMaxHealth() - p.getCurrentHealth();
				// the smallest potion that covers the missing HP first, then bigger ones, then smaller ones
				Found f = best(sp, s -> {
					if (!(s.getItem() instanceof PotionItem pi) || !pi.canUseOnPokemon(s, p)) return -1;
					int o = pi.getType().ordinal(), heal = POTION_HEAL[Math.min(o, POTION_HEAL.length - 1)];
					return heal >= missing ? o : 100 - o;
				});
				if (f == null) { if (guard == 0) missingItems.add(name + " needs a Potion"); break; }
				String item = itemName(f);
				int before = p.getCurrentHealth();
				useOne(sp, f, stack -> ((PotionItem) stack.getItem()).applyToPokemon(sp, stack, p));
				if (p.getCurrentHealth() <= before) break;
				used.add(item + " → " + name);
			}
			if (!p.isFainted() && p.getStatus() != null) {
				Found f = best(sp, s -> s.getItem() instanceof StatusCureItem c && c.canUseOnPokemon(s, p)
					? (ItemKeys.key(s).contains("full_heal") ? 1 : 0) : -1);
				if (f == null) { missingItems.add(name + " needs a status cure"); continue; }
				String item = itemName(f);
				useOne(sp, f, stack -> ((StatusCureItem) stack.getItem()).applyToPokemon(sp, stack, p));
				used.add(item + " → " + name);
			}
		}
		JsonObject r = Api.ok();
		r.add("used", used);
		r.add("missing", missingItems);
		return r;
	}

	private interface Rank { int of(ItemStack s); }
	private interface Apply { void on(ItemStack s) throws Exception; }

	/** The lowest-ranked usable stack (rank < 0 = not usable); inventory before backpacks on ties. */
	private static Found best(ServerPlayer sp, Rank rank) {
		Found best = null;
		int bestR = Integer.MAX_VALUE;
		for (Found f : scan(sp)) {
			int r = rank.of(f.stack());
			if (r < 0) continue;
			if (r < bestR || (r == bestR && best != null && best.inPack() && !f.inPack())) { best = f; bestR = r; }
		}
		return best;
	}

	private static String itemName(Found f) { return f.stack().getHoverName().getString(); }

	/** Uses one item where it is (inventory) or takes one out of the backpack and returns it if unused. */
	private static void useOne(ServerPlayer sp, Found f, Apply apply) throws Exception {
		ItemStack stack = f.inPack() ? Backpacks.extract(f.pack(), 1) : f.stack();
		if (stack.isEmpty()) return;
		apply.on(stack);
		if (f.inPack() && !stack.isEmpty()) giveBack(sp, f, stack);
		sp.getInventory().setChanged();
	}

	static PokemonEntity wildTarget(PokemonBattle battle) {
		for (BattleActor a : battle.getActors()) {
			if (a.getType() != ActorType.WILD) continue;
			for (ActiveBattlePokemon ap : a.getActivePokemon()) {
				BattlePokemon bp = ap.getBattlePokemon();
				if (bp != null && bp.getEntity() != null && bp.getHealth() > 0) return bp.getEntity();
			}
		}
		return null;
	}

	/** Throws one ball at the wild Pokémon, aiming the arc the way Cobblemon launches it. */
	private static JsonObject throwBall(ServerPlayer sp, String key) throws Exception {
		PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(sp);
		PokemonEntity target = battle != null ? wildTarget(battle) : null;
		if (target == null) return Api.error("balls can only be thrown at a wild Pokémon in battle");
		Found f = find(sp, key);
		if (f == null || !(f.stack().getItem() instanceof PokeBallItem pbi)) return Api.error("you don't have that ball any more");
		ItemStack one = f.inPack() ? Backpacks.extract(f.pack(), 1) : f.stack().split(1);
		if (one.isEmpty()) return Api.error("couldn't take the ball out");
		float[] aim = aim(sp, target.getBoundingBox().getCenter(), pbi.getPokeBall().getThrowPower());
		var inv = sp.getInventory();
		ItemStack hand = inv.getItem(inv.selected);
		sp.setYRot(aim[0]);
		sp.setXRot(aim[1]);
		sp.setYHeadRot(aim[0]);
		inv.setItem(inv.selected, one);
		try {
			one.use(sp.level(), sp, InteractionHand.MAIN_HAND);
		} finally {
			ItemStack left = inv.getItem(inv.selected);
			inv.setItem(inv.selected, hand);
			if (!left.isEmpty()) giveBack(sp, f, left); // not thrown: put it back
			inv.setChanged();
		}
		JsonObject r = Api.ok();
		r.addProperty("yaw", aim[0]);
		r.addProperty("pitch", aim[1]);
		return r;
	}

	/**
	 * Yaw and pitch that land a ball on {@code to}: Cobblemon launches it 5° above the look direction
	 * (scaled by cos(pitch) when looking up) at the ball's throw power; vanilla projectile physics
	 * (gravity 0.03, drag 0.99 per tick). Tries pitches and keeps the closest pass.
	 */
	static float[] aim(ServerPlayer sp, Vec3 to, float power) {
		Vec3 eye = sp.getEyePosition();
		double dx = to.x - eye.x, dz = to.z - eye.z;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		float best = 0;
		double bestD = Double.MAX_VALUE;
		for (float pitch = -60f; pitch <= 45f; pitch += 0.25f) {
			float launch = pitch < 0 ? pitch - 5f * Mth.cos(pitch * Mth.DEG_TO_RAD) : pitch - 5f;
			float yr = yaw * Mth.DEG_TO_RAD, pr = launch * Mth.DEG_TO_RAD;
			Vec3 v = new Vec3(-Mth.sin(yr) * Mth.cos(pr), -Mth.sin(pr), Mth.cos(yr) * Mth.cos(pr)).scale(power);
			Vec3 p = eye.add(v.normalize());
			double d = p.distanceTo(to);
			for (int t = 0; t < 80; t++) {
				p = p.add(v);
				v = v.scale(0.99).add(0, -0.03, 0);
				d = Math.min(d, p.distanceTo(to));
				if (p.y < to.y - 4) break;
			}
			if (d < bestD) { bestD = d; best = pitch; }
		}
		return new float[]{yaw, best};
	}
}
