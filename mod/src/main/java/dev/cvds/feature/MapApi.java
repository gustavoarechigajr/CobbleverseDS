package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** The Map tab's data and actions (client thread). */
final class MapApi {
	private MapApi() {}

	static JsonObject info(Minecraft mc) {
		if (mc.player == null || mc.level == null) return Api.error("not in game");
		JsonObject o = Api.ok();
		String dim = MapTiles.dimId(mc.level);
		o.addProperty("dim", dim);
		o.addProperty("mappable", MapTiles.mappable(mc.level));
		o.addProperty("x", mc.player.getX());
		o.addProperty("z", mc.player.getZ());
		o.addProperty("yaw", mc.player.getYRot());
		o.addProperty("tile", MapTiles.TILE);
		o.add("tiles", MapTiles.tiles(dim));
		o.add("waypoints", Waypoints.json());
		o.addProperty("xaero", Waypoints.LOADED);
		JsonArray mons = new JsonArray();
		if (Snapshot.COBBLEMON) {
			try { mons = MapMons.nearby(mc, 96); } catch (Throwable ignored) {}
		}
		o.add("mons", mons);
		return o;
	}

	static JsonObject act(Minecraft mc, JsonObject a) throws Exception {
		String op = a.get("op").getAsString();
		switch (op) {
			case "addWaypoint" -> {
				int x = a.get("x").getAsInt(), z = a.get("z").getAsInt();
				String name = a.has("name") ? a.get("name").getAsString() : "Waypoint";
				return Waypoints.add(x, MapTiles.surfaceY(mc, x, z), z, name);
			}
			case "removeWaypoint" -> { return Waypoints.remove(a.get("i").getAsInt()); }
			case "teleport" -> {
				int x = a.get("x").getAsInt(), z = a.get("z").getAsInt();
				int y = a.has("y") ? a.get("y").getAsInt() : MapTiles.surfaceY(mc, x, z) + 1;
				Chat.send(mc, "/tp @s " + x + " " + y + " " + z); // needs cheats / op; the game says so if not
				return Api.ok();
			}
			default -> { return Api.error("unknown map op " + op); }
		}
	}
}
