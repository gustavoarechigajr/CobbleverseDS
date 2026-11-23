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
		if (req.method().equals("GET") && STATIC.containsKey(path)) return asset(STATIC.get(path));
		if (path.equals("/api/state")) {
			long since = parseLong(req.query().get("rev"), -1);
			long wait = Math.min(parseLong(req.query().get("wait"), 1500), 5000);
			return Response.json(Snapshot.await(since, wait));
		}
		if (path.startsWith("/api/icon/")) {
			byte[] png = Icons.get(path.substring("/api/icon/".length()), 3000);
			return png == null ? Response.notFound() : new Response(200, "image/png", png, Map.of("Cache-Control", "max-age=86400"));
		}
		Minecraft mc = Minecraft.getInstance();
		switch (path) {
			case "/api/keys": return onClient(mc, () -> mc.options == null ? error("not ready") : wrap("keys", Keys.list(mc)));
			case "/api/chat": return onClient(mc, () -> mc.player == null ? error("not in game") : wrap("lines", Chat.recent(mc, 30)));
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
			case "/api/battle": {
				if (!Snapshot.COBBLEMON) return Response.json(error("cobblemon not installed").toString());
				return onClient(mc, () -> mc.player == null ? error("not in game") : Battle.json(mc));
			}
			case "/api/recipes": {
				String q = req.query().getOrDefault("q", "");
				return onClient(mc, () -> mc.player == null ? error("not in game") : Crafting.search(mc, q, 60));
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
		if (mc.player == null || mc.gameMode == null) return error("not in game");
		String kind = a.has("kind") ? a.get("kind").getAsString() : "inv";
		return switch (kind) {
			case "inv" -> InvOps.run(mc, a);
			case "craft" -> Crafting.craft(mc, a.get("id").getAsString(), a.has("max") && a.get("max").getAsBoolean());
			case "key" -> Keys.press(mc, a.get("id").getAsString()) ? ok() : error("no such key");
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
		try {
			r = mc.submit(() -> {
				try { return c.call(); }
				catch (Exception e) { return error(e.getClass().getSimpleName() + ": " + e.getMessage()); }
			}).get(5, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			r = error("game did not respond in time");
		}
		return Response.json(r.toString());
	}

	interface ClientText { String call() throws Exception; }

	static Response onClientRaw(Minecraft mc, ClientText c) throws Exception {
		String r;
		try {
			r = mc.submit(() -> {
				try { return c.call(); }
				catch (Exception e) { return error(e.getClass().getSimpleName() + ": " + e.getMessage()).toString(); }
			}).get(5, TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			r = error("game did not respond in time").toString();
		}
		return Response.json(r);
	}

	static JsonObject wrap(String field, JsonElement e) { JsonObject o = ok(); o.add(field, e); return o; }

	static long parseLong(String s, long dflt) {
		if (s == null) return dflt;
		try { return Long.parseLong(s); } catch (NumberFormatException e) { return dflt; }
	}

	static Response asset(String name) throws Exception {
		try (InputStream in = Api.class.getResourceAsStream("/assets/cvds/web/" + name)) {
			if (in == null) return Response.notFound();
			String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8";
			return new Response(200, type, in.readAllBytes(), Map.of());
		}
	}
}
