package dev.cvds.feature;

import com.google.gson.*;
import dev.cvds.CvdsClient;
import dev.cvds.net.MiniHttpServer.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/** HTTP routes. Anything touching the game is handed to the client thread and awaited. */
public final class Api implements dev.cvds.net.MiniHttpServer.Handler {
	static final Map<String, String> STATIC = Map.of(
		"/", "index.html", "/index.html", "index.html", "/app.js", "app.js", "/app.css", "app.css");

	public static JsonObject ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return o; }
	public static JsonObject error(String msg) { JsonObject o = new JsonObject(); o.addProperty("ok", false); o.addProperty("error", msg); return o; }

	@Override
	public Response handle(Request req) throws Exception {
		String path = req.path();
		// Only our own page may talk to us: a web page in the phone's browser could otherwise
		// POST text/plain "simple requests" here (CSRF), or reach us by DNS rebinding.
		if (!localHost(req.headers().get("host"))) return Response.text(403, "text/plain", "bad host");
		String origin = req.headers().get("origin");
		if (origin != null && !origin.equals("null") && !localHost(origin.replaceFirst("^https?://", ""))) return Response.text(403, "text/plain", "bad origin");
		if (req.method().equals("POST") && !"1".equals(req.headers().get("x-cvds"))) return Response.text(403, "text/plain", "missing X-CVDS header");
		if (req.method().equals("GET") && STATIC.containsKey(path)) return asset(STATIC.get(path));
		if (req.method().equals("GET") && path.matches("/fonts/[a-z0-9-]+\\.woff2")) return asset(path.substring(1));
		if (path.equals("/api/state")) {
			long since = parseLong(req.query().get("rev"), -1);
			long wait = Math.min(parseLong(req.query().get("wait"), 1500), 5000);
			return Response.json(Snapshot.await(since, wait));
		}
		if (path.equals("/api/mirror")) {
			// newest menu frame after ?after=<seq>; ?hi=1 for full resolution (zoomed in)
			Object[] f = Mirror.await(parseLong(req.query().get("after"), 0), "1".equals(req.query().get("hi")), 1500);
			if (f == null) return Response.text(204, "text/plain", "");
			return new Response(200, "image/png", (byte[]) f[0], Map.of("Cache-Control", "no-store", "X-Seq", String.valueOf(f[1]),
				"X-Size", f[2] + "x" + f[3], "Access-Control-Expose-Headers", "X-Seq, X-Size"));
		}
		if (path.startsWith("/api/maptile/")) {
			// /api/maptile/<dim>/<tx>/<tz>  (dim URL-encoded, e.g. minecraft%3Aoverworld)
			String[] p = path.substring("/api/maptile/".length()).split("/");
			if (p.length != 3) return Response.notFound();
			String dim = java.net.URLDecoder.decode(p[0], java.nio.charset.StandardCharsets.UTF_8);
			byte[] png = MapTiles.png(dim, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
			return png == null ? Response.notFound() : new Response(200, "image/png", png, Map.of("Cache-Control", "no-store"));
		}
		if (path.startsWith("/api/icon/")) {
			byte[] png = Icons.get(path.substring("/api/icon/".length()), 3000);
			return png == null ? Response.notFound() : new Response(200, "image/png", png, Map.of("Cache-Control", "max-age=86400"));
		}
		Minecraft mc = Minecraft.getInstance();
		switch (path) {
			case "/api/keys": return onClient(mc, () -> mc.options == null ? error("not ready") : wrap("keys", Keys.list(mc)));
			case "/api/chat": return onClient(mc, () -> {
				if (mc.player == null) return error("not in game");
				JsonObject o = wrap("lines", Chat.recent(mc, 30));
				o.add("rich", Chat.rich(mc, 30));
				return o;
			});
			case "/api/pc": {
				if (!Snapshot.COBBLEMON) return Response.json(error("cobblemon not installed").toString());
				String since = req.query().getOrDefault("sig", "");
				return onClientRaw(mc, () -> {
					if (mc.player == null) return error("not in game").toString();
					String body = Pc.json(mc).toString();
					String sig = Integer.toHexString(body.hashCode());
					// Unchanged since the caller's copy: skip resending a whole PC.
					return sig.equals(since) ? "{\"same\":true,\"sig\":\"" + sig + "\"}" : "{\"sig\":\"" + sig + "\"," + body.substring(1);
				});
			}
			case "/api/terminal": {
				if (!Snapshot.TOMS) return Response.json(error("Tom's Simple Storage not installed").toString());
				String since = req.query().getOrDefault("sig", "");
				return onClientRaw(mc, () -> {
					if (mc.player == null) return error("not in game").toString();
					String body = Terminal.json(mc).toString();
					String sig = Integer.toHexString(body.hashCode());
					return sig.equals(since) ? "{\"same\":true,\"sig\":\"" + sig + "\"}" : "{\"sig\":\"" + sig + "\"," + body.substring(1);
				});
			}
			case "/api/spawns": {
				if (!Snapshot.COBBLEMON) return Response.json(error("cobblemon not installed").toString());
				if (mc.player == null) return Response.json(error("not in game").toString());
				return Response.json(Spawns.query(mc).toString());
			}
			case "/api/battle": {
				if (!Snapshot.COBBLEMON) return Response.json(error("cobblemon not installed").toString());
				return onClient(mc, () -> mc.player == null ? error("not in game") : Battle.json(mc));
			}
			case "/api/bag": {
				// Battle bag: works on the integrated server, so it waits here, not on the client thread.
				if (!Snapshot.COBBLEMON) return Response.json(error("cobblemon not installed").toString());
				if (mc.player == null) return Response.json(error("not in game").toString());
				if (req.method().equals("POST"))
					return Response.json(BattleBag.act(mc, JsonParser.parseString(req.bodyText()).getAsJsonObject()).toString());
				return Response.json(BattleBag.list(mc).toString());
			}
			case "/api/pack": {
				// Backpack view: like the bag, it works on the integrated server and waits here.
				if (mc.player == null) return Response.json(error("not in game").toString());
				if (req.method().equals("POST"))
					return Response.json(BackpackView.act(mc, JsonParser.parseString(req.bodyText()).getAsJsonObject()).toString());
				return Response.json(BackpackView.list(mc).toString());
			}
			case "/api/map": return onClient(mc, () -> MapApi.info(mc));
			case "/api/pokemon": {
				if (!Snapshot.COBBLEMON) return Response.json(error("cobblemon not installed").toString());
				int slot = (int) parseLong(req.query().get("slot"), 0);
				return onClient(mc, () -> mc.player == null ? error("not in game") : PokemonPage.json(slot));
			}
			case "/api/recipes": {
				String q = req.query().getOrDefault("q", "");
				Set<String> favs = new HashSet<>();
				for (String f : req.query().getOrDefault("fav", "").split(",")) if (!f.isBlank()) favs.add(f.trim());
				return onClient(mc, () -> mc.player == null ? error("not in game") : Crafting.search(mc, q, 60, favs));
			}
			case "/api/action": {
				if (!req.method().equals("POST")) return Response.text(405, "text/plain", "POST only");
				JsonObject a = JsonParser.parseString(req.bodyText()).getAsJsonObject();
				return onClient(mc, () -> action(mc, a));
			}
			case "/api/debug": {
				if (!CvdsClient.config.debug) return Response.text(403, "text/plain", "debug disabled");
				JsonObject a = JsonParser.parseString(req.bodyText()).getAsJsonObject();
				return onClient(mc, () -> Debug.run(mc, a));
			}
			default: return Response.notFound();
		}
	}

	static JsonObject action(Minecraft mc, JsonObject a) {
		String kind = a.has("kind") ? a.get("kind").getAsString() : "inv";
		if (kind.equals("pointer")) return Pointer.act(mc, a); // menus outside a world too (title, world list)
		if (mc.player == null || mc.gameMode == null) return error("not in game");
		return switch (kind) {
			case "inv" -> InvOps.run(mc, a);
			case "craft" -> Crafting.craft(mc, a.get("id").getAsString(), a.has("max") && a.get("max").getAsBoolean(), a.has("count") ? a.get("count").getAsInt() : 1);
			case "key" -> (a.has("hold") ? Keys.hold(mc, a.get("id").getAsString(), a.get("hold").getAsBoolean())
				: Keys.press(mc, a.get("id").getAsString())) ? ok() : error("no such key");
			case "chat" -> { Chat.send(mc, a.get("text").getAsString()); yield ok(); }
			case "party" -> {
				if (!Snapshot.COBBLEMON) yield error("cobblemon not installed");
				int slot = a.get("slot").getAsInt();
				if (a.has("send") && a.get("send").getAsBoolean()) Party.sendOut(mc, slot); else Party.select(slot);
				yield ok();
			}
			case "terminal" -> Snapshot.TOMS ? Terminal.take(mc, a.get("k").getAsString(), a.has("one") && a.get("one").getAsBoolean()) : error("Tom's Simple Storage not installed");
			case "battle" -> Snapshot.COBBLEMON ? Battle.choose(mc, a) : error("cobblemon not installed");
			case "pc" -> Snapshot.COBBLEMON ? Pc.action(mc, a) : error("cobblemon not installed");
			case "summary" -> Snapshot.COBBLEMON ? Screens.act(mc, a) : error("cobblemon not installed");
			case "pokemon" -> Snapshot.COBBLEMON ? PokemonPage.act(mc, a) : error("cobblemon not installed");
			case "pointer" -> Pointer.act(mc, a);
			case "map" -> { try { yield MapApi.act(mc, a); } catch (Exception e) { yield error(e.getClass().getSimpleName() + ": " + e.getMessage()); } }
			case "hotbarSelect" -> {
				int s = a.get("slot").getAsInt();
				if (s < 0 || s > 8) yield error("0-8");
				mc.player.getInventory().selected = s;
				yield ok();
			}
			default -> error("unknown kind " + kind);
		};
	}

	interface ClientCall { JsonObject call() throws Exception; }

	static Response onClient(Minecraft mc, ClientCall c) throws Exception {
		JsonObject r;
		var f = mc.submit(() -> {
			try { return c.call(); }
			catch (Exception e) { return error(e.getClass().getSimpleName() + ": " + e.getMessage()); }
		});
		try {
			r = f.get(5, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			// Don't let it run later behind the UI's back (a retry would then do it twice).
			r = error(f.cancel(false) ? "game did not respond in time" : "game was slow; the action may still have applied");
		}
		return Response.json(r.toString());
	}

	interface ClientText { String call() throws Exception; }

	static Response onClientRaw(Minecraft mc, ClientText c) throws Exception {
		String r;
		var f = mc.submit(() -> {
			try { return c.call(); }
			catch (Exception e) { return error(e.getClass().getSimpleName() + ": " + e.getMessage()).toString(); }
		});
		try {
			r = f.get(5, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			f.cancel(false);
			r = error("game did not respond in time").toString();
		}
		return Response.json(r);
	}

	static boolean localHost(String host) {
		if (host == null) return false;
		String h = host.toLowerCase(java.util.Locale.ROOT);
		int port = CvdsClient.config.port;
		return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port)
			// tests reach the Thor through "adb forward" on another local port
			|| h.matches("(127\\.0\\.0\\.1|localhost):\\d+");
	}

	static JsonObject wrap(String field, JsonElement e) { JsonObject o = ok(); o.add(field, e); return o; }

	static long parseLong(String s, long dflt) {
		if (s == null) return dflt;
		try { return Long.parseLong(s); } catch (NumberFormatException e) { return dflt; }
	}

	static Response asset(String name) throws Exception {
		try (InputStream in = Api.class.getResourceAsStream("/assets/cvds/web/" + name)) {
			if (in == null) return Response.notFound();
			if (name.endsWith(".woff2")) return new Response(200, "font/woff2", in.readAllBytes(), Map.of("Cache-Control", "max-age=604800"));
			String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8";
			return new Response(200, type, in.readAllBytes(), Map.of());
		}
	}
}
