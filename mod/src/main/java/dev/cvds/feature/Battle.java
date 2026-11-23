package dev.cvds.feature;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Cobblemon battles: both sides' active Pokémon and the move/switch choices for the player's
 * pending request. Choosing works like Cobblemon's own battle screen: set the request's
 * response, then let the battle send everything once all requests are answered.
 */
final class Battle {
	private Battle() {}

	static ClientBattleSide mySide(Minecraft mc, ClientBattle b) {
		UUID me = mc.player.getUUID();
		for (ClientBattleSide side : b.getSides())
			for (var actor : side.getActors()) if (actor.getUuid().equals(me)) return side;
		return b.getSide1();
	}

	static JsonObject json(Minecraft mc) {
		JsonObject o = new JsonObject();
		ClientBattle b = CobblemonClient.INSTANCE.getBattle();
		o.addProperty("active", b != null);
		if (b == null) return o;
		o.addProperty("spectating", b.getSpectating());
		o.addProperty("wild", b.getWildActor() != null);
		o.addProperty("minimised", b.getMinimised());
		ClientBattleSide mine = mySide(mc, b);
		ClientBattleSide theirs = mine == b.getSide1() ? b.getSide2() : b.getSide1();
		o.add("mine", side(mine));
		o.add("theirs", side(theirs));
		SingleActionRequest req = b.getMustChoose() ? b.getFirstUnansweredRequest() : null;
		if (req != null) {
			JsonObject r = new JsonObject();
			r.addProperty("forceSwitch", req.getForceSwitch());
			JsonArray moves = new JsonArray();
			if (req.getMoveSet() != null) {
				r.addProperty("trapped", req.getMoveSet().getTrapped());
				for (InBattleMove mv : req.getMoveSet().getMoves()) {
					JsonObject m = new JsonObject();
					m.addProperty("id", mv.getId());
					m.addProperty("pp", mv.getPp());
					m.addProperty("maxPp", mv.getMaxpp());
					m.addProperty("disabled", mv.getDisabled());
					MoveTemplate t = Moves.INSTANCE.getByName(mv.getId());
					m.addProperty("name", t != null ? t.getDisplayName().getString() : mv.getMove());
					// Legal targets (double battles need one picked); null target list = no target needed.
					try {
						var tl = mv.getTarget().getTargetList().invoke(req.getActivePokemon());
						JsonArray ts = new JsonArray();
						if (tl != null) for (var tg : tl) {
							JsonObject x = new JsonObject();
							x.addProperty("pnx", tg.getPNX());
							x.addProperty("ally", tg.isAllied(req.getActivePokemon()));
							if (tg instanceof ActiveClientBattlePokemon ap && ap.getBattlePokemon() != null)
								x.addProperty("name", ap.getBattlePokemon().getDisplayName().getString());
							ts.add(x);
						}
						m.add("targets", ts);
					} catch (Throwable ignored) {}
					if (t != null) {
						try { m.addProperty("desc", t.getDescription().getString()); } catch (Throwable ignored) {}
						m.addProperty("type", t.getElementalType().getName());
						m.addProperty("cat", t.getDamageCategory().getName());
						m.addProperty("power", t.getPower());
						m.addProperty("acc", t.getAccuracy());
					}
					moves.add(m);
				}
			}
			r.add("moves", moves);
			// Bench Pokémon that can be switched in.
			JsonArray bench = new JsonArray();
			var party = CobblemonClient.INSTANCE.getStorage().getParty();
			for (Pokemon p : party.getSlots()) {
				if (p == null) continue;
				JsonObject e = new JsonObject();
				e.addProperty("id", p.getUuid().toString());
				e.addProperty("name", p.getDisplayName(false).getString());
				e.addProperty("lvl", p.getLevel());
				e.addProperty("hp", p.getCurrentHealth());
				e.addProperty("maxHp", p.getMaxHealth());
				e.addProperty("active", isActive(mine, p.getUuid()));
				bench.add(e);
			}
			r.add("bench", bench);
			r.addProperty("singles", b.getBattleFormat().getBattleType().getSlotsPerActor() == 1);
			o.add("request", r);
		}
		return o;
	}

	static boolean isActive(ClientBattleSide side, UUID id) {
		for (ActiveClientBattlePokemon a : side.getActiveClientBattlePokemon()) {
			ClientBattlePokemon p = a.getBattlePokemon();
			if (p != null && p.getUuid().equals(id)) return true;
		}
		return false;
	}

	static JsonArray side(ClientBattleSide side) {
		JsonArray arr = new JsonArray();
		for (ActiveClientBattlePokemon a : side.getActiveClientBattlePokemon()) {
			ClientBattlePokemon p = a.getBattlePokemon();
			if (p == null) continue;
			JsonObject m = new JsonObject();
			m.addProperty("name", p.getDisplayName().getString());
			m.addProperty("species", p.getSpecies().getTranslatedName().getString());
			m.addProperty("pnx", a.getPNX());
			// Sprite: the same high-res Pokémon render the party uses.
			try { m.addProperty("k", ItemKeys.key(com.cobblemon.mod.common.item.PokemonItem.Companion.from(p.getProperties(), 1))); } catch (Throwable ignored) {}
			m.addProperty("lvl", p.getLevel());
			// isHpFlat: absolute HP (own side); otherwise a 0..1 fraction (opponents).
			m.addProperty("hp", p.getHpValue());
			m.addProperty("maxHp", p.getMaxHp());
			m.addProperty("flat", p.isHpFlat());
			if (p.getStatus() != null) m.addProperty("status", p.getStatus().getShowdownName());
			var form = p.getSpecies().getForm(p.getProperties().getAspects());
			JsonArray types = new JsonArray();
			types.add(form.getPrimaryType().getName());
			if (form.getSecondaryType() != null) types.add(form.getSecondaryType().getName());
			m.add("types", types);
			JsonObject stages = new JsonObject();
			p.getStatChanges().forEach((stat, v) -> { if (v != 0) stages.addProperty(stat.getShowdownId(), v); });
			m.add("stages", stages);
			Boolean shiny = p.getProperties().getShiny();
			m.addProperty("shiny", shiny != null && shiny);
			arr.add(m);
		}
		return arr;
	}

	static JsonObject choose(Minecraft mc, JsonObject a) {
		ClientBattle b = CobblemonClient.INSTANCE.getBattle();
		if (b == null) return Api.error("not in a battle");
		String op = a.get("op").getAsString();
		// "Move around": what Cobblemon's own key does in BattleGUI (minimizeBattle / PartySendBinding).
		// Minimised, the battle screen fades out and closes itself; the battle keeps running.
		if (op.equals("hide")) {
			b.setMinimised(true);
			return Api.ok();
		}
		if (op.equals("show")) {
			b.setMinimised(false);
			if (!(mc.screen instanceof BattleGUI)) mc.setScreen(new BattleGUI());
			return Api.ok();
		}
		SingleActionRequest req = b.getMustChoose() ? b.getFirstUnansweredRequest() : null;
		if (req == null) return Api.error("nothing to choose right now");
		if (op.equals("move")) {
			if (req.getForceSwitch() || req.getMoveSet() == null) return Api.error("you have to switch");
			String id = a.get("id").getAsString();
			InBattleMove mv = req.getMoveSet().getMoves().stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
			if (mv == null) return Api.error("unknown move");
			if (mv.getDisabled() || mv.getPp() <= 0) return Api.error("that move can't be used");
			// Same targeting rule as Cobblemon's battle screen: the move's target type gives the
			// legal targets (null = no target needed, e.g. self or field moves). The panel picks one in
			// double battles; otherwise the first opponent.
			String pnx = null;
			var targets = mv.getTarget().getTargetList().invoke(req.getActivePokemon());
			if (targets != null && !targets.isEmpty()) {
				String want = a.has("target") ? a.get("target").getAsString() : null;
				var t = want != null ? targets.stream().filter(x -> want.equals(x.getPNX())).findFirst().orElse(null)
					: targets.stream().filter(x -> !x.isAllied(req.getActivePokemon())).findFirst().orElse(targets.get(0));
				if (t == null) return Api.error("that target can't be hit by this move");
				pnx = t.getPNX();
			}
			req.setResponse(new MoveActionResponse(id, pnx, null));
		} else if (op.equals("switch")) {
			if (req.getMoveSet() != null && req.getMoveSet().getTrapped()) return Api.error("can't switch: trapped");
			UUID id = UUID.fromString(a.get("id").getAsString());
			if (isActive(mySide(mc, b), id)) return Api.error("already in battle");
			Pokemon p = CobblemonClient.INSTANCE.getStorage().getParty().findByUUID(id);
			if (p == null || p.isFainted()) return Api.error("can't switch to that Pokémon");
			req.setResponse(new SwitchActionResponse(id));
		} else return Api.error("unknown battle op " + op);
		b.checkForFinishedChoosing();
		return Api.ok();
	}
}
