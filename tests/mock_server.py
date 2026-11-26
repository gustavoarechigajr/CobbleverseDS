#!/usr/bin/env python3
"""Serves the touch UI with sample data, for designing/reviewing it without a game running.

    python3 -I tests/mock_server.py [--port 25611] [--scene chest|terminal|craft|none] [--battle]
Item icons come from Minecraft's own item textures (from the Gradle cache), when available.
"""
import argparse, glob, io, json, os, threading, time, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

WEB = os.path.join(os.path.dirname(__file__), "../mod/src/main/resources/assets/cvds/web")
ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, default=25611)
ap.add_argument("--scene", default="chest")
ap.add_argument("--battle", action="store_true")
args = ap.parse_args()

jars = glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/*/minecraft-client.jar")) + \
       glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/**/minecraft-client*.jar"), recursive=True) + \
       glob.glob(os.path.expanduser("~/Projects/cobbleverse-ds/mod/.gradle/loom-cache/minecraftMaven/**/*.jar"), recursive=True)
textures = None
TEX_LOCK = threading.Lock()  # ZipFile isn't safe to read from several threads
for j in jars:
    try:
        z = zipfile.ZipFile(j)
        if "assets/minecraft/textures/item/diamond.png" in z.namelist():
            textures = z
            break
    except Exception:
        pass

ITEMS = {
    "minecraft:diamond": "Diamond", "minecraft:emerald": "Emerald", "minecraft:iron_ingot": "Iron Ingot",
    "minecraft:redstone": "Redstone Dust", "minecraft:bread": "Bread", "minecraft:apple": "Apple",
    "minecraft:torch": "Torch", "minecraft:stick": "Stick", "minecraft:iron_pickaxe": "Iron Pickaxe",
    "minecraft:diamond_sword": "Diamond Sword", "minecraft:ender_pearl": "Ender Pearl", "minecraft:book": "Book",
    "minecraft:golden_apple": "Golden Apple", "minecraft:coal": "Coal", "minecraft:string": "String",
    "minecraft:oak_boat": "Oak Boat", "minecraft:fishing_rod": "Fishing Rod", "minecraft:paper": "Paper",
}


def slot(i, x, y, g, k=None, n=1, d=None):
    s = {"i": i, "x": x, "y": y, "g": g}
    if k:
        s["k"], s["n"] = k, n
    if d is not None:
        s["d"] = d
    return s


def inventory(offset, filled=True):
    out = []
    inv = [("minecraft:bread", 12), ("minecraft:torch", 40), None, ("minecraft:coal", 23), None, ("minecraft:string", 9)] + [None] * 21
    hot = [("minecraft:iron_pickaxe", 1), ("minecraft:diamond_sword", 1), ("minecraft:apple", 5), None, ("minecraft:fishing_rod", 1), None, None, ("minecraft:oak_boat", 1), ("minecraft:paper", 3)]
    for r in range(3):
        for c in range(9):
            it = inv[r * 9 + c] if filled else None
            out.append(slot(offset + r * 9 + c, 8 + c * 18, 84 + r * 18, "p", *(it or (None,))))
    for c in range(9):
        it = hot[c] if filled else None
        out.append(slot(offset + 27 + c, 8 + c * 18, 142, "h", *(it or (None,)), d=(0.62 if c == 0 else None)))
    return out


def chest():
    stock = {0: ("minecraft:diamond", 12), 1: ("minecraft:emerald", 3), 4: ("minecraft:ender_pearl", 9), 9: ("minecraft:redstone", 40),
             13: ("minecraft:golden_apple", 2), 20: ("minecraft:book", 7), 22: ("minecraft:iron_ingot", 31)}
    c = [slot(i, 8 + (i % 9) * 18, 18 + (i // 9) * 18, "c", *(stock.get(i) or (None,))) for i in range(27)]
    return c + inventory(27)


def state():
    scene = args.scene
    menu = {"id": 3, "slots": chest()} if scene == "chest" else {"id": 0, "slots": inventory(9), "grid": [2, 2]}
    if scene == "terminal":
        menu = {"id": 5, "slots": inventory(0)}
    party = [
        {"id": "a", "name": "Pikachu", "species": "Pikachu", "lvl": 24, "hp": 51, "maxHp": 58, "types": ["Electric"], "held": "Light Ball"},
        {"id": "b", "name": "Lucario", "species": "Lucario", "lvl": 33, "hp": 30, "maxHp": 96, "types": ["Fighting", "Steel"], "status": "brn"},
        {"id": "c", "name": "Gengar", "species": "Gengar", "lvl": 40, "hp": 0, "maxHp": 104, "types": ["Ghost", "Poison"], "fainted": True},
        {"id": "d", "name": "Eevee", "species": "Eevee", "lvl": 12, "hp": 37, "maxHp": 37, "types": ["Normal"], "shiny": True}, {}, {},
    ]
    return {
        "inGame": True,
        "screen": {"kind": "container" if scene in ("chest", "terminal") else "none", "title": "Chest" if scene == "chest" else "S. Terminal", "terminal": scene == "terminal"},
        "menu": menu,
        "items": {k: {"name": v, "max": 64} for k, v in ITEMS.items()},
        "status": {"x": 979, "y": 78, "z": 89, "biomeName": "Snowy Plains", "day": 4, "time": 1350, "rain": False, "thunder": False,
                   "canSleep": False, "hp": 18, "maxHp": 20, "food": 17, "xp": 23, "hotbar": 0, "fps": 60},
        "party": {"sel": 0, "slots": party},
        "battle": args.battle,
    }


def pc():
    mons = ["Growlithe", "Ponyta", "Vulpix", "Charizard", "Gengar", "Lucario", "Garchomp", "Umbreon", "Sinistea", "Varoom", "Burmy"]
    box = [{"id": f"p{i}", "name": n, "species": n, "dex": 58 + i, "lvl": 5 + 7 * i, "hp": 20, "maxHp": 20, "shiny": i == 3, "gender": "MALE",
            "types": ["Fire"] if i < 4 else ["Ghost"], "nature": "Quiet", "ability": "Flash Fire",
            "ivs": {"HP": 28, "ATTACK": 25, "DEFENCE": 15, "SPECIAL_ATTACK": 31, "SPECIAL_DEFENCE": 1, "SPEED": 19}, "ivTotal": 119,
            "moves": ["Ember", "Bite", "Leer", "Flame Wheel"]} for i, n in enumerate(mons)]
    return {"sig": "s1", "open": True, "party": state()["party"]["slots"], "boxes": [{"name": "Box 1", "slots": box + [{}] * (30 - len(box))}] + [{"name": f"Box {i}", "slots": [{}] * 30} for i in range(2, 31)],
            "items": {}, "sortModes": ["NAME", "LEVEL", "TYPE", "POKEDEX_NUMBER", "GENDER"]}


MINIMISED = [False]


def battle():
    return {"active": True, "minimised": MINIMISED[0],
            "mine": [{"name": "Pikachu", "species": "Pikachu", "lvl": 24, "hp": 51, "maxHp": 58, "flat": True, "types": ["Electric"], "stages": {"spa": 1}}],
            "theirs": [{"name": "Gyarados", "species": "Gyarados", "lvl": 20, "hp": 0.64, "maxHp": 1, "flat": False, "types": ["Water", "Flying"], "status": "par", "stages": {"atk": -1}}],
            "request": {"singles": True, "moves": [
                {"id": "thunderbolt", "name": "Thunderbolt", "pp": 12, "maxPp": 15, "type": "Electric", "cat": "special", "power": 90, "acc": 100},
                {"id": "quickattack", "name": "Quick Attack", "pp": 30, "maxPp": 30, "type": "Normal", "cat": "physical", "power": 40, "acc": 100},
                {"id": "irontail", "name": "Iron Tail", "pp": 15, "maxPp": 15, "type": "Steel", "cat": "physical", "power": 100, "acc": 75},
                {"id": "thunderwave", "name": "Thunder Wave", "pp": 20, "maxPp": 20, "type": "Electric", "cat": "status", "power": 0, "acc": 90}],
                "bench": [{"id": "b", "name": "Lucario", "lvl": 33, "hp": 30, "maxHp": 96}, {"id": "c", "name": "Gengar", "lvl": 40, "hp": 0, "maxHp": 104}, {"id": "a", "name": "Pikachu", "lvl": 24, "hp": 51, "maxHp": 58, "active": True}]}}


SPAWNS = {"ok": True, "items": {}, "buckets": [
    {"bucket": "common", "list": [{"name": "Snorunt", "pct": 41.2, "dex": 361, "types": "ice", "dexState": "CAUGHT"}, {"name": "Swinub", "pct": 33.5, "dex": 220, "types": "ice/ground", "dexState": "ENCOUNTERED"}, {"name": "Burmy", "pct": 25.3, "dex": 412, "types": "bug", "dexState": "NONE"}]},
    {"bucket": "uncommon", "list": [{"name": "Sinistea", "pct": 73.48, "dex": 854, "types": "ghost", "dexState": "NONE"}, {"name": "Varoom", "pct": 26.52, "dex": 965, "types": "steel/poison", "dexState": "NONE"}]},
    {"bucket": "rare", "list": [{"name": "Snover", "pct": 100, "dex": 459, "types": "grass/ice", "dexState": "CAUGHT"}]},
    {"bucket": "ultra-rare", "list": []}]}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        p = u.path
        if p in ("/", "/index.html", "/app.js", "/app.css") or p.startswith("/fonts/"):
            f = os.path.join(WEB, "index.html" if p == "/" else p.lstrip("/"))
            ctype = {"html": "text/html", "js": "text/javascript", "css": "text/css", "woff2": "font/woff2"}[f.rsplit(".", 1)[1]]
            return self.send(200, open(f, "rb").read(), ctype)
        if p == "/api/state":
            if parse_qs(u.query).get("rev", ["-1"])[0] == "7":
                time.sleep(1.5)
            return self.send(200, dict(rev=7, **state()))
        if p.startswith("/api/icon/"):
            key = unquote(p[len("/api/icon/"):])
            if textures and key.startswith("minecraft:"):
                name = f"assets/minecraft/textures/item/{key.split(':')[1]}.png"
                with TEX_LOCK:
                    data = textures.read(name) if name in textures.namelist() else None
                if data:
                    return self.send(200, data, "image/png")
            return self.send(404, b"no", "text/plain")
        if p == "/api/pc": return self.send(200, pc())
        if p == "/api/bag": return self.send(200, {"ok": True, "inBattle": True, "wild": True, "canAct": True, "backpacks": True,
          "items": [{"k": "cobblemon:max_revive", "name": "Max Revive", "inv": 64, "pack": 0, "inGame": False,
                     "targets": [{"id": "g", "name": "Gengar", "hp": 0, "maxHp": 104}]},
                    {"k": "cobblemon:super_potion", "name": "Super Potion", "inv": 2, "pack": 5, "inGame": False,
                     "targets": [{"id": "a", "name": "Pikachu", "hp": 51, "maxHp": 58}, {"id": "b", "name": "Lucario", "hp": 30, "maxHp": 96}]},
                    {"k": "cobblemon:full_heal", "name": "Full Heal", "inv": 0, "pack": 3, "inGame": False, "targets": []},
                    {"k": "cobblemon:ether", "name": "Ether", "inv": 1, "pack": 0, "inGame": True, "targets": []}],
          "balls": [{"k": "cobblemon:poke_ball", "name": "Poké Ball", "inv": 8, "pack": 24, "chance": 9},
                    {"k": "cobblemon:great_ball", "name": "Great Ball", "inv": 0, "pack": 6, "chance": 22},
                    {"k": "cobblemon:ultra_ball", "name": "Ultra Ball", "inv": 3, "pack": 0, "chance": 41}]})
        if p == "/api/battle": return self.send(200, battle() if args.battle else {"active": False})
        if p == "/api/spawns": return self.send(200, SPAWNS)
        if p == "/api/terminal":
            lst = [{"k": k, "n": n} for k, n in [("minecraft:diamond", 1234), ("minecraft:iron_ingot", 512), ("minecraft:redstone", 2048), ("minecraft:coal", 96), ("minecraft:book", 14), ("minecraft:apple", 33), ("minecraft:emerald", 7), ("minecraft:string", 151)]]
            return self.send(200, {"sig": "t", "open": True, "list": lst, "items": {k: {"name": v} for k, v in ITEMS.items()}, "free": 41, "slots": 108})
        if p == "/api/keys":
            ks = [("key.inventory", "Open/Close Inventory", "Inventory", "E"), ("key.cobblemon.throwpartypokemon", "Throw Selected Pokémon", "Cobblemon", "R"),
                  ("key.cobblemon.summary", "Open Summary", "Cobblemon", "M"), ("key.cobblemon.pokedex", "Open Pokédex", "Cobblemon", "K"),
                  ("key.lumymon.access_pc", "Access Your PC", "LumyMon", "P"), ("key.jump", "Jump", "Movement", "Space"), ("key.sneak", "Sneak", "Movement", "Left Shift"), ("gui.xaero_open_map", "Open World Map", "Xaero's World Map", "M")]
            return self.send(200, {"ok": True, "keys": [{"id": a, "label": b, "cat": c, "bound": d} for a, b, c, d in ks]})
        if p == "/api/recipes":
            rs = [("minecraft:torch", "Torch", 4, True, ["1× Coal", "1× Stick"]), ("minecraft:book", "Book", 1, True, ["3× Paper", "1× Leather"]), ("minecraft:fishing_rod", "Fishing Rod", 1, True, ["3× Stick", "2× String"]), ("minecraft:iron_pickaxe", "Iron Pickaxe", 1, False, ["3× Iron Ingot", "2× Stick"])]
            return self.send(200, {"ok": True, "recipes": [{"id": k, "k": k, "name": n, "n": c, "can": can, "ings": ing} for k, n, c, can, ing in rs], "items": {}})
        if p == "/api/chat":
            return self.send(200, {"ok": True, "lines": ["<Trainer> anyone near the ice cave?", "[Server: Set the time to 1000]", "A wild Sinistea appeared!", "<Trainer> got it"]})
        self.send(404, {"ok": False, "error": "nope"})

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            a = json.loads(body or b"{}")
            if a.get("kind") == "battle" and a.get("op") in ("hide", "show"): MINIMISED[0] = a["op"] == "hide"
        except Exception:
            pass
        self.send(200, {"ok": True})


print(f"mock UI on http://127.0.0.1:{args.port}/  (icons: {'yes' if textures else 'no'})")
ThreadingHTTPServer(("127.0.0.1", args.port), H).serve_forever()
