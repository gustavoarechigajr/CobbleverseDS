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
		String dim = MapTiles.layerKey(mc.level); // the surface, or the cave layer you are in
		o.addProperty("dim", dim);
		o.addProperty("mappable", MapTiles.mappable(mc.level));
		int band = MapTiles.caveBand();
		if (band != Integer.MIN_VALUE) {
			o.addProperty("cave", true);
			o.addProperty("caveFrom", Math.max(mc.level.getMinBuildHeight(), band * MapTiles.BAND + MapTiles.BAND - 1 - MapTiles.CAVE_SCAN));
			o.addProperty("caveTo", band * MapTiles.BAND + MapTiles.BAND - 1);
		}
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
				// on a cave map the spot is down here with you, not on the surface above
				int y = MapTiles.caveBand() != Integer.MIN_VALUE && mc.player != null ? mc.player.getBlockY() : MapTiles.surfaceY(mc, x, z);
				return Waypoints.add(x, y, z, name);
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
