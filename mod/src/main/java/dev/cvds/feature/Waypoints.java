package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cvds.CvdsClient;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Xaero's Minimap waypoints, read and written through Xaero's own in-memory API (reflection keeps it a
 * soft dependency), so the panel's map and Xaero's always show the same list. Client thread only.
 */
final class Waypoints {
	private Waypoints() {}
	static final boolean LOADED = FabricLoader.getInstance().isModLoaded("xaerominimap");
	private static boolean warned = false;

	private static Object call(Object o, String name, Object... args) throws Exception {
		outer:
		for (Method m : o.getClass().getMethods()) {
			if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
			Class<?>[] pt = m.getParameterTypes();
			for (int i = 0; i < args.length; i++) {
				Class<?> want = pt[i].isPrimitive() ? (pt[i] == int.class ? Integer.class : pt[i] == boolean.class ? Boolean.class : pt[i]) : pt[i];
				if (args[i] != null && !want.isInstance(args[i])) continue outer; // e.g. remove(int) vs remove(Waypoint)
			}
			return m.invoke(o, args);
		}
		throw new NoSuchMethodException(o.getClass().getName() + "." + name);
	}

	/** [session, world, set] or null. */
	private static Object[] current() throws Exception {
		Class<?> mods = Class.forName("xaero.hud.minimap.BuiltInHudModules");
		Object module = mods.getField("MINIMAP").get(null);
		Object session = call(module, "getCurrentSession");
		if (session == null) return null;
		Object world = call(call(session, "getWorldManager"), "getCurrentWorld");
		if (world == null) return null;
		Object set = call(world, "getCurrentWaypointSet");
		return set == null ? null : new Object[]{session, world, set};
	}

	private static List<Object> list(Object set) throws Exception {
		List<Object> out = new ArrayList<>();
		for (Object w : (Iterable<?>) call(set, "getWaypoints")) out.add(w);
		return out;
	}

	static JsonArray json() {
		JsonArray a = new JsonArray();
		if (!LOADED) return a;
		try {
			Object[] c = current();
			if (c == null) return a;
			int i = 0;
			for (Object w : list(c[2])) {
				JsonObject o = new JsonObject();
				o.addProperty("i", i++);
				o.addProperty("name", String.valueOf(call(w, "getLocalizedName")));
				o.addProperty("initials", String.valueOf(call(w, "getInitials")));
				o.addProperty("x", (int) call(w, "getX"));
				o.addProperty("y", (int) call(w, "getY"));
				o.addProperty("z", (int) call(w, "getZ"));
				a.add(o);
			}
		} catch (Throwable t) {
			if (!warned) { warned = true; CvdsClient.LOG.warn("cvds: could not read Xaero waypoints", t); }
		}
		return a;
	}

	static JsonObject add(int x, int y, int z, String name) throws Exception {
		if (!LOADED) return Api.error("Xaero's Minimap isn't installed");
		Object[] c = current();
		if (c == null) return Api.error("Xaero has no waypoint set for this world yet");
		String initials = name.isBlank() ? "X" : name.trim().substring(0, 1).toUpperCase();
		Class<?> wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
		Object wp = wpClass.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class)
			.newInstance(x, y, z, name, initials, (int) (Math.random() * 15) + 1);
		call(c[2], "add", wp);
		save(c);
		return Api.ok();
	}

	static JsonObject remove(int index) throws Exception {
		if (!LOADED) return Api.error("Xaero's Minimap isn't installed");
		Object[] c = current();
		if (c == null) return Api.error("no waypoints");
		List<Object> l = list(c[2]);
		if (index < 0 || index >= l.size()) return Api.error("that waypoint is gone");
		call(c[2], "remove", l.get(index));
		save(c);
		return Api.ok();
	}

	private static void save(Object[] c) throws Exception {
		call(call(c[0], "getWorldManagerIO"), "saveWorld", c[1]);
	}
}
