#!/usr/bin/env python3
"""End-to-end tests for the Cobbleverse DS mod against a running game.

Needs the game started with -Dcvds.debug=true and loaded into a THROWAWAY world: the tests
change the player's gamemode, inventory and nearby blocks.

    python3 -I tests/integration.py [--base http://127.0.0.1:25599] [--skip-craft]
"""
import argparse
import json
import sys
import time
import traceback
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:25599"


def http(path, body=None, timeout=10):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST" if body is not None else "GET",
                                 headers={"X-CVDS": "1", "Content-Type": "application/json"} if body is not None else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        return raw if r.headers.get("Content-Type", "").startswith("image/") else json.loads(raw)


def state(since=-1, wait=0):
    return http(f"/api/state?rev={since}&wait={wait}")


def action(**a):
    return http("/api/action", a)


def debug(**a):
    r = http("/api/debug", a)
    assert r.get("ok"), r
    return r


def wait_for(pred, timeout=5.0, what="condition"):
    deadline = time.time() + timeout
    s = state()
    while time.time() < deadline:
        try:
            if pred(s):
                return s
        except (KeyError, TypeError, IndexError):
            pass
        s = state(s["rev"], 500)
    raise AssertionError(f"timed out waiting for {what}; last screen={s.get('screen')}")


def settle():
    """Waits until the state stops changing (server confirmations arrive a few ticks later)."""
    s = state()
    for _ in range(20):
        n = state(s["rev"], 400)
        if n["rev"] == s["rev"]:
            return n
        s = n
    return s


def slots(s, group=None):
    return [x for x in s["menu"]["slots"] if group is None or x["g"] in group]


def slot(s, i):
    return next(x for x in s["menu"]["slots"] if x["i"] == i)


def totals(s, groups=("c", "p", "h")):
    t = {}
    for x in slots(s, groups):
        if x.get("k"):
            t[x["k"]] = t.get(x["k"], 0) + x["n"]
    return t


def count(s, key, groups=("p", "h")):
    return sum(x.get("n", 0) for x in slots(s, groups) if x.get("k") == key)


def no_cursor(s):
    assert "carried" not in s["menu"], f"item left on cursor: {s['menu'].get('carried')}"


def mid(s):
    return s["menu"]["id"]


class Env:
    def __init__(self, world=None):
        if world:
            deadline = time.time() + 300
            while time.time() < deadline:
                try:
                    if state().get("inGame"):
                        break
                    r = http("/api/debug", {"op": "enterWorld", "name": world})
                    if r.get("ok"):
                        break
                except OSError:
                    pass
                time.sleep(2)
        s = wait_for(lambda s: s.get("inGame"), 600, "in-game state")
        time.sleep(3)  # let chunks and the player settle
        # Fixed spot on the flat test world, so block placement never depends on where we spawned.
        self.px, self.py, self.pz = 0, -60, 0

    def cmd(self, c):
        debug(op="cmd", cmd=c)

    def reset(self):
        debug(op="closeScreen")
        self.cmd("fill -5 -61 -5 5 -61 5 minecraft:stone")
        self.cmd("fill -5 -60 -5 5 -55 5 minecraft:air")
        self.cmd("kill @e[type=!minecraft:player,distance=..40]")
        self.cmd("tp @a 0 -60 0 0 0")
        self.cmd("gamemode survival @a")
        self.cmd("clear @a")
        self.cmd("effect give @a minecraft:resistance 600 4 true")
        self.cmd("time set 1000")
        self.cmd("weather clear")
        settle()

    def chest(self, items, dx=2):
        x, y, z = self.px + dx, self.py, self.pz
        self.cmd(f"setblock {x} {y} {z} minecraft:air")
        nbt = ",".join(f'{{Slot:{s}b,id:"{i}",count:{c}}}' for s, (i, c) in items.items())
        self.cmd(f"setblock {x} {y} {z} minecraft:chest{{Items:[{nbt}]}}")
        time.sleep(0.3)
        return self.open(x, y, z)

    def block(self, block, dx=-2):
        x, y, z = self.px + dx, self.py, self.pz
        self.cmd(f"setblock {x} {y} {z} {block}")
        time.sleep(0.3)
        return self.open(x, y, z)

    def open(self, x, y, z, pred=None):
        debug(op="closeScreen")
        settle()
        debug(op="use", x=x, y=y, z=z)
        pred = pred or (lambda s: s["screen"]["kind"] == "container" and any(x["g"] in "cg" for x in s["menu"]["slots"]))
        return wait_for(pred, 5, "container screen")


TESTS = []


def test(fn):
    TESTS.append(fn)
    return fn


@test
def state_and_long_poll(env):
    s = state()
    assert s["inGame"] and "status" in s and "menu" in s
    t = time.time()
    n = state(s["rev"], 1200)
    took = time.time() - t
    assert n["rev"] != s["rev"] or 1.0 <= took <= 3.0, f"long-poll returned too early/late: {took:.2f}s"
    page = urllib.request.urlopen(BASE + "/", timeout=5).read().decode()
    assert "Cobbleverse DS" in page
    assert len(urllib.request.urlopen(BASE + "/app.js", timeout=5).read()) > 1000


@test
def bad_requests_are_rejected_cleanly(env):
    env.reset()
    s = settle()
    for bad in ({"kind": "inv", "op": "move", "from": 9999, "to": 1},
                {"kind": "inv", "op": "nope"},
                {"kind": "inv", "op": "hotbar", "slot": 9, "hotbar": 12},
                {"kind": "inv", "op": "quick", "slot": 9, "menu": 4242},
                {"kind": "zzz"},
                {"kind": "key", "id": "key.does.not.exist"}):
        r = action(**bad)
        assert r["ok"] is False and r.get("error"), (bad, r)
    try:
        http("/api/action", None)
        raise AssertionError("GET /api/action should fail")
    except urllib.error.HTTPError as e:
        assert e.code == 405
    try:
        urllib.request.urlopen(urllib.request.Request(BASE + "/api/action", data=b"{not json", method="POST", headers={"X-CVDS": "1"}), timeout=5)
        raise AssertionError("bad JSON should fail")
    except urllib.error.HTTPError as e:
        assert e.code == 500
    assert settle()["inGame"]


@test
def rejects_cross_site_requests(env):
    """A web page in the phone's browser must not be able to drive the game."""
    env.reset()
    def status(path, body=None, headers=None):
        req = urllib.request.Request(BASE + path, data=body, method="POST" if body is not None else "GET", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code
    chat = json.dumps({"kind": "chat", "text": "forged"}).encode()
    assert status("/api/action", chat, {"Content-Type": "text/plain"}) == 403, "POST without X-CVDS must be refused"
    assert status("/api/action", chat, {"X-CVDS": "1", "Origin": "http://evil.example"}) == 403
    assert status("/api/state?rev=-1", headers={"Host": "evil.example:25599"}) == 403, "DNS rebinding host must be refused"
    assert status("/api/state?rev=-1", headers={"Origin": "http://evil.example"}) == 403
    assert status("/api/action", chat.replace(b"forged", b"x"), {"X-CVDS": "1", "Origin": BASE}) == 200
    assert not any("forged" in l for l in http("/api/chat")["lines"])


@test
def chest_shows_container_and_player_slots(env):
    env.reset()
    s = env.chest({0: ("minecraft:stone", 40), 5: ("minecraft:dirt", 7)})
    assert len(slots(s, "c")) == 27, len(slots(s, "c"))
    assert len(slots(s, "p")) == 27 and len(slots(s, "h")) == 9
    assert slot(s, 0)["k"] == "minecraft:stone" and slot(s, 0)["n"] == 40
    assert s["items"]["minecraft:stone"]["name"] == "Stone"


@test
def move_all_half_one(env):
    env.reset()
    s = env.chest({0: ("minecraft:stone", 40)})
    p0, p1, p2 = [x["i"] for x in slots(s, "p")[:3]]
    action(kind="inv", op="move", **{"from": 0}, to=p0, amount="half", menu=mid(s))
    s = settle()
    assert slot(s, p0)["n"] == 20 and slot(s, 0)["n"] == 20, (slot(s, p0), slot(s, 0))
    action(kind="inv", op="move", **{"from": 0}, to=p1, amount="one", menu=mid(s))
    s = settle()
    assert slot(s, p1)["n"] == 1 and slot(s, 0)["n"] == 19
    action(kind="inv", op="move", **{"from": 0}, to=p2, amount="all", menu=mid(s))
    s = settle()
    assert slot(s, p2)["n"] == 19 and not slot(s, 0).get("k")
    no_cursor(s)
    assert totals(s)["minecraft:stone"] == 40


@test
def move_onto_other_item_swaps(env):
    env.reset()
    s = env.chest({0: ("minecraft:stone", 10), 1: ("minecraft:dirt", 3)})
    action(kind="inv", op="move", **{"from": 0}, to=1, amount="all", menu=mid(s))
    s = settle()
    assert slot(s, 0)["k"] == "minecraft:dirt" and slot(s, 1)["k"] == "minecraft:stone"
    no_cursor(s)


@test
def quick_move_and_bulk(env):
    env.reset()
    s = env.chest({0: ("minecraft:stone", 64), 1: ("minecraft:dirt", 10), 2: ("minecraft:stone", 5)})
    action(kind="inv", op="quick", slot=1, menu=mid(s))
    s = settle()
    assert count(s, "minecraft:dirt") == 10 and not slot(s, 1).get("k")
    action(kind="inv", op="quickAll", group="c", menu=mid(s))
    s = settle()
    assert count(s, "minecraft:stone") == 69 and not slots(s, "c")[0].get("k")
    action(kind="inv", op="quickAll", group="p", menu=mid(s))  # main inventory only, not hotbar
    s = settle()
    assert totals(s, ("c",)).get("minecraft:stone", 0) + count(s, "minecraft:stone") == 69
    no_cursor(s)


@test
def store_matching_only_moves_matching(env):
    env.reset()
    env.cmd("give @a minecraft:oak_log 20")
    env.cmd("give @a minecraft:cobblestone 30")
    s = env.chest({4: ("minecraft:oak_log", 1)})
    action(kind="inv", op="quickAll", group="pc", key="minecraft:oak_log", menu=mid(s))
    s = settle()
    assert totals(s, ("c",))["minecraft:oak_log"] == 21
    assert count(s, "minecraft:cobblestone") == 30
    assert "minecraft:cobblestone" not in totals(s, ("c",))


@test
def sort_container_conserves_and_orders(env):
    env.reset()
    items = {0: ("minecraft:stone", 13), 3: ("minecraft:apple", 2), 7: ("minecraft:stone", 50), 9: ("minecraft:dirt", 64),
             12: ("minecraft:apple", 30), 20: ("minecraft:diamond_sword", 1), 26: ("minecraft:dirt", 1), 15: ("minecraft:ender_pearl", 9),
             16: ("minecraft:ender_pearl", 10), 22: ("minecraft:diamond_sword", 1)}
    s = env.chest(items)
    before = totals(s)
    r = action(kind="inv", op="sort", group="c", menu=mid(s))
    assert r["ok"], r
    s = settle()
    assert totals(s) == before, (before, totals(s))
    no_cursor(s)
    c = slots(s, "c")
    names = [s["items"][x["k"]]["name"].lower() for x in c if x.get("k")]
    assert names == sorted(names), names
    filled = [bool(x.get("k")) for x in c]
    assert filled == sorted(filled, reverse=True), "sorted container has gaps"
    pearls = [x["n"] for x in c if x.get("k") == "minecraft:ender_pearl"]
    assert pearls == [16, 3], pearls


@test
def sort_inventory(env):
    env.reset()
    for c in ("minecraft:stone 3", "minecraft:apple 5", "minecraft:stone 70", "minecraft:bread 4"):
        env.cmd("give @a " + c)
    s = env.chest({})
    hotbar_before = [(x.get("k"), x.get("n")) for x in slots(s, "h")]
    before = totals(s)
    assert action(kind="inv", op="sort", group="p", menu=mid(s))["ok"]
    s = settle()
    assert totals(s) == before
    assert [(x.get("k"), x.get("n")) for x in slots(s, "h")] == hotbar_before, "sorting the inventory must not touch the hotbar"


@test
def hotbar_swap_and_drop(env):
    env.reset()
    s = env.chest({0: ("minecraft:torch", 32)})
    hb3 = slots(s, "h")[3]["i"]
    action(kind="inv", op="hotbar", slot=0, hotbar=3, menu=mid(s))
    s = settle()
    assert slot(s, hb3)["k"] == "minecraft:torch" and slot(s, hb3)["n"] == 32
    action(kind="inv", op="drop", slot=hb3, all=False, menu=mid(s))
    s = settle()
    assert slot(s, hb3)["n"] == 31
    action(kind="inv", op="drop", slot=hb3, all=True, menu=mid(s))
    s = settle()
    assert not slot(s, hb3).get("k")


@test
def works_with_no_screen_open(env):
    env.reset()
    env.cmd("give @a minecraft:stone 10")
    s = settle()
    assert s["screen"]["kind"] == "none" and s["menu"]["id"] == 0
    src = next(x["i"] for x in slots(s, "ph") if x.get("k") == "minecraft:stone")
    dst = slots(s, "p")[-1]["i"]
    action(kind="inv", op="move", **{"from": src}, to=dst, amount="half", menu=0)
    s = settle()
    assert slot(s, dst)["n"] == 5 and count(s, "minecraft:stone") == 10
    no_cursor(s)


@test
def menu_change_is_detected(env):
    env.reset()
    s = env.chest({0: ("minecraft:stone", 1)})
    stale = mid(s)
    debug(op="closeScreen")
    settle()
    r = action(kind="inv", op="quick", slot=0, menu=stale)
    assert r["ok"] is False and "menu" in r["error"], r


@test
def icons_render_as_png(env):
    env.reset()
    s = env.chest({0: ("minecraft:stone", 1), 1: ("minecraft:diamond_sword", 1), 2: ("minecraft:chest", 1)})
    for k in ("minecraft:stone", "minecraft:diamond_sword", "minecraft:chest"):
        png = http("/api/icon/" + urllib.request.quote(k, safe=""), timeout=10)
        assert png[:8] == b"\x89PNG\r\n\x1a\n", k
        w, h = int.from_bytes(png[16:20], "big"), int.from_bytes(png[20:24], "big")
        assert (w, h) == (64, 64), (k, w, h)
        assert len(png) > 300, f"{k} icon looks blank ({len(png)} bytes)"
    try:
        http("/api/icon/minecraft%3Anever_seen_item")
        raise AssertionError("unknown icon should 404")
    except urllib.error.HTTPError as e:
        assert e.code == 404


@test
def damaged_tools_share_one_key(env):
    """Durability must not split item keys (and icons): it is shown as a bar instead."""
    env.reset()
    s = env.chest({0: ("minecraft:iron_pickaxe", 1), 1: ("minecraft:iron_pickaxe", 1)})
    env.cmd('item replace block %d %d %d container.1 with minecraft:iron_pickaxe[damage=100]' % (env.px + 2, env.py, env.pz))
    s = wait_for(lambda s: slot(s, 1).get("d") is not None, 5, "damaged pickaxe")
    assert slot(s, 0)["k"] == slot(s, 1)["k"] == "minecraft:iron_pickaxe", (slot(s, 0), slot(s, 1))
    assert "d" not in slot(s, 0) and abs(slot(s, 1)["d"] - (250 - 100) / 250) < 0.01, (slot(s, 0), slot(s, 1))


@test
def keybinds_list_and_press(env):
    env.reset()
    keys = http("/api/keys")["keys"]
    ids = {k["id"] for k in keys}
    assert "key.inventory" in ids and "key.jump" in ids, len(ids)
    assert all(k["label"] and k["cat"] for k in keys)
    assert action(kind="key", id="key.inventory")["ok"]
    s = wait_for(lambda s: s["screen"]["kind"] == "container", 3, "inventory screen")
    # Class names are obfuscated outside dev, so check the menu instead: the inventory is menu 0.
    assert s["menu"]["id"] == 0 and s["menu"].get("grid") == [2, 2], s["screen"]


@test
def hotbar_select(env):
    env.reset()
    action(kind="hotbarSelect", slot=6)
    wait_for(lambda s: s["status"]["hotbar"] == 6, 3, "hotbar 6")
    assert action(kind="hotbarSelect", slot=12)["ok"] is False


@test
def chat_round_trip(env):
    env.reset()
    msg = f"cvds test {int(time.time())}"
    assert action(kind="chat", text=msg)["ok"]
    deadline = time.time() + 5
    while time.time() < deadline:
        if any(msg in line for line in http("/api/chat")["lines"]):
            return
        time.sleep(0.3)
    raise AssertionError("chat message never showed up")


@test
def crafting_2x2_unlocked_recipe(env):
    env.reset()
    env.cmd("recipe give @a *")
    env.cmd("give @a minecraft:oak_log 3")
    s = settle()
    assert s["menu"].get("grid") == [2, 2], s["menu"].get("grid")
    deadline = time.time() + 10
    while True:  # the recipe book sync can lag behind the command in big modpacks
        rs = http("/api/recipes?q=oak%20planks")
        r = next(r for r in rs["recipes"] if r["k"] == "minecraft:oak_planks")
        if r["can"] or time.time() > deadline:
            break
        time.sleep(0.5)
    assert r["can"], r
    assert action(kind="craft", id=r["id"], max=False)["ok"]
    s = wait_for(lambda s: count(s, "minecraft:oak_planks") == 4, 5, "4 planks")
    assert count(s, "minecraft:oak_log") == 2
    assert action(kind="craft", id=r["id"], max=True)["ok"]
    s = wait_for(lambda s: count(s, "minecraft:oak_planks") == 12, 5, "12 planks")
    assert count(s, "minecraft:oak_log") == 0
    no_cursor(s)
    assert not any(x.get("k") for x in slots(s, "gr")), "grid not empty after crafting"


@test
def crafting_table_locked_recipe(env):
    """3x3 recipe the player hasn't unlocked: must still craft (ingredients placed by clicks)."""
    env.reset()
    env.cmd("recipe take @a *")
    env.cmd("give @a minecraft:oak_planks 20")
    s = env.block("minecraft:crafting_table")
    assert s["menu"].get("grid") == [3, 3]
    deadline = time.time() + 10
    while True:  # inventory sync can lag in big modpacks
        rs = http("/api/recipes?q=bowl")
        r = next(r for r in rs["recipes"] if r["id"] == "minecraft:bowl")
        if r["can"] or time.time() > deadline:
            break
        time.sleep(0.5)
    assert r["can"] and any("Plank" in i for i in r["ings"]), r
    assert action(kind="craft", id=r["id"], max=False)["ok"]
    s = wait_for(lambda s: count(s, "minecraft:bowl") == 4, 5, "4 bowls")
    assert count(s, "minecraft:oak_planks") == 17
    res = action(kind="craft", id=r["id"], max=True)
    assert res["ok"] and res["sets"] == 5, res
    s = wait_for(lambda s: count(s, "minecraft:bowl") == 24, 5, "24 bowls")
    assert count(s, "minecraft:oak_planks") == 2
    no_cursor(s)
    assert not any(x.get("k") for x in slots(s, "g")), "grid not empty after crafting"
    r = action(kind="craft", id="minecraft:bowl", max=False)
    assert r["ok"] is False and "missing" in r["error"], r


@test
def recipe_search_without_grid_explains(env):
    env.reset()
    s = env.chest({})
    rs = http("/api/recipes?q=stick")
    assert "error" in rs


@test
def party_panel(env):
    """Cobblemon only: party contents, selection and send-out key."""
    env.reset()
    if "party" not in state():
        print("  (skipped: Cobblemon not loaded)")
        return
    env.cmd("execute as @a run clearparty @s")
    env.cmd("execute as @a run clearpc @s")
    env.cmd("execute as @a run pokegive @s pikachu level=12")
    env.cmd("execute as @a run pokegive @s bulbasaur level=5")
    s = wait_for(lambda s: sum(1 for m in s["party"]["slots"] if m.get("name")) >= 2, 8, "two party members")
    names = [m.get("species", "").lower() for m in s["party"]["slots"] if m.get("name")]
    assert "pikachu" in names and "bulbasaur" in names, names
    pika = next(m for m in s["party"]["slots"] if m.get("species", "").lower() == "pikachu")
    assert pika["lvl"] == 12 and pika["hp"] > 0 and pika["maxHp"] >= pika["hp"] and "electric" in [t.lower() for t in pika["types"]], pika
    slot_b = next(i for i, m in enumerate(s["party"]["slots"]) if m.get("species", "").lower() == "bulbasaur")
    assert action(kind="party", slot=slot_b)["ok"]
    wait_for(lambda s: s["party"]["sel"] == slot_b, 3, "party selection")
    assert action(kind="party", slot=9)["ok"] is False
    keys = {k["id"] for k in http("/api/keys")["keys"]}
    assert "key.cobblemon.throwpartypokemon" in keys


def pcs(sig=""):
    return http("/api/pc?sig=" + sig)


def wait_pc(pred, timeout=6, what="pc state"):
    deadline = time.time() + timeout
    while True:
        p = pcs()
        try:
            if pred(p):
                return p
        except (KeyError, IndexError, TypeError):
            pass
        if time.time() > deadline:
            raise AssertionError(f"timed out waiting for {what}")
        time.sleep(0.4)


def names(slots):
    return [m.get("species") for m in slots]


@test
def pc_boxes_moves_release_sort(env):
    """Cobblemon only: PC contents, moves between PC and party, release safety, sort."""
    env.reset()
    if "party" not in state():
        print("  (skipped: Cobblemon not loaded)")
        return
    env.cmd("execute as @a run clearparty @s")
    env.cmd("execute as @a run clearpc @s")
    mons = ["pikachu", "bulbasaur", "charmander", "squirtle", "eevee", "magikarp", "zubat", "abra"]
    for m in mons:
        env.cmd(f"execute as @a run pokegive @s {m} level=10")
    env.cmd("execute as @a run pc")
    wait_for(lambda s: s["screen"]["kind"] == "pc", 6, "PC screen")
    p = wait_pc(lambda p: p["open"] and sum(1 for m in p["boxes"][0]["slots"] if m.get("id")) == 2, what="2 Pokémon in box 1")
    assert len(p["party"]) == 6 and all(m.get("id") for m in p["party"]), names(p["party"])
    assert len(p["boxes"][0]["slots"]) == 30 and p["sortModes"]
    box0 = [m for m in p["boxes"][0]["slots"] if m.get("id")]
    assert {m["species"].lower() for m in box0} == {"zubat", "abra"}, names(box0)
    m0 = next(m for m in p["party"] if m.get("k"))
    png = http("/api/icon/" + urllib.request.quote(m0["k"], safe=""), timeout=10)
    assert png[:4] == b"\x89PNG" and len(png) > 500, "Pokémon model icon missing or blank"
    assert set(m0["ivs"]) == {"HP", "ATTACK", "DEFENCE", "SPECIAL_ATTACK", "SPECIAL_DEFENCE", "SPEED"} and m0["moves"], m0

    def ref_of(p, species):
        for bi, b in enumerate(p["boxes"]):
            for si, m in enumerate(b["slots"]):
                if m.get("species", "").lower() == species:
                    return {"type": "pc", "box": bi, "slot": si}
        for si, m in enumerate(p["party"]):
            if m.get("species", "").lower() == species:
                return {"type": "party", "slot": si}
        raise AssertionError(species + " not found")

    # PC -> empty PC slot in another box
    assert action(kind="pc", op="move", **{"from": ref_of(p, "zubat")}, to={"type": "pc", "box": 1, "slot": 5})["ok"]
    p = wait_pc(lambda p: p["boxes"][1]["slots"][5].get("species", "").lower() == "zubat", what="zubat in box 2")
    # PC <-> party swap
    party0 = p["party"][0]["species"].lower()
    assert action(kind="pc", op="move", **{"from": {"type": "pc", "box": 1, "slot": 5}}, to={"type": "party", "slot": 0})["ok"]
    p = wait_pc(lambda p: p["party"][0]["species"].lower() == "zubat" and p["boxes"][1]["slots"][5].get("species", "").lower() == party0, what="pc/party swap")
    # party <-> party swap
    a, b = p["party"][1]["species"], p["party"][2]["species"]
    assert action(kind="pc", op="move", **{"from": {"type": "party", "slot": 1}}, to={"type": "party", "slot": 2})["ok"]
    p = wait_pc(lambda p: p["party"][1]["species"] == b and p["party"][2]["species"] == a, what="party swap")
    # release needs the exact Pokémon id
    abra = ref_of(p, "abra")
    bad = action(kind="pc", op="release", at=abra, id="00000000-0000-0000-0000-000000000000")
    assert bad["ok"] is False, bad
    abra_id = p["boxes"][abra["box"]]["slots"][abra["slot"]]["id"]
    total = lambda p: sum(1 for b in p["boxes"] for m in b["slots"] if m.get("id")) + sum(1 for m in p["party"] if m.get("id"))
    before = total(p)
    assert action(kind="pc", op="release", at=abra, id=abra_id)["ok"]
    p = wait_pc(lambda p: total(p) == before - 1, what="release")
    assert all(m.get("id") != abra_id for b in p["boxes"] for m in b["slots"])
    # sort box 1 by name
    for m in ["vulpix", "growlithe", "ponyta"]:
        env.cmd(f"execute as @a run pokegive @s {m} level=5")
    p = wait_pc(lambda p: sum(1 for m in p["boxes"][0]["slots"] if m.get("id")) >= 3, what="3 in box 1")
    assert action(kind="pc", op="sort", box=0, mode="NAME")["ok"]
    p = wait_pc(lambda p: [m["name"] for m in p["boxes"][0]["slots"] if m.get("id")] == sorted(m["name"] for m in p["boxes"][0]["slots"] if m.get("id"))
                and p["boxes"][0]["slots"][0].get("id"), what="sorted box")
    # unchanged state is not resent
    assert pcs(p["sig"]).get("same") is True
    # actions need an open PC
    debug(op="closeScreen")
    wait_for(lambda s: s["screen"]["kind"] != "pc", 4, "PC closed")
    r = action(kind="pc", op="move", **{"from": {"type": "pc", "box": 0, "slot": 0}}, to={"type": "pc", "box": 2, "slot": 0})
    assert r["ok"] is False and "PC" in r["error"], r


def term(sig=""):
    return http("/api/terminal?sig=" + sig)


def net_count(t, key):
    return sum(e["n"] for e in t.get("list", []) if e["k"] == key)


@test
def toms_storage_terminal(env):
    """Tom's Simple Storage only: browse the network, take a stack / one, store, craft from the network."""
    env.reset()
    if term().get("ok") is False:
        print("  (skipped: Tom's Simple Storage not loaded)")
        return
    # chest A + chest B -> inventory connector <- terminal ("facing" points at the inventory it reads)
    env.cmd('setblock 4 -60 0 minecraft:chest{Items:[{Slot:0b,id:"minecraft:stone",count:64},{Slot:1b,id:"minecraft:oak_log",count:10}]}')
    env.cmd('setblock 3 -60 1 minecraft:chest{Items:[{Slot:0b,id:"minecraft:stone",count:36},{Slot:3b,id:"minecraft:dirt",count:5}]}')
    env.cmd("setblock 3 -60 0 toms_storage:inventory_connector")
    env.cmd("setblock 2 -60 0 toms_storage:storage_terminal[facing=east]")
    time.sleep(0.5)
    s = env.open(2, -60, 0, lambda s: s["screen"].get("terminal") is True)
    deadline = time.time() + 8
    while True:
        t = term()
        if net_count(t, "minecraft:stone") == 100 or time.time() > deadline:
            break
        time.sleep(0.4)
    assert t["open"] and net_count(t, "minecraft:stone") == 100 and net_count(t, "minecraft:dirt") == 5, t.get("list")
    assert t["items"]["minecraft:oak_log"]["name"] == "Oak Log"
    assert term(t["sig"]).get("same") is True
    assert action(kind="terminal", k="minecraft:stone")["ok"]
    s = wait_for(lambda s: count(s, "minecraft:stone") == 64, 5, "64 stone pulled")
    assert action(kind="terminal", k="minecraft:dirt", one=True)["ok"]
    s = wait_for(lambda s: count(s, "minecraft:dirt") == 1, 5, "1 dirt pulled")
    assert not any(x.get("k") for x in slots(s, "a")), "take-one must not leave items in the offhand"
    t = term()
    assert net_count(t, "minecraft:stone") == 36 and net_count(t, "minecraft:dirt") == 4, t["list"]
    no_cursor(s)
    # store: shift-click a player stack back into the network
    src = next(x["i"] for x in slots(s, "ph") if x.get("k") == "minecraft:stone")
    action(kind="inv", op="quick", slot=src, menu=mid(s))
    s = wait_for(lambda s: count(s, "minecraft:stone") == 0, 5, "stone stored")
    deadline = time.time() + 5
    while net_count(term(), "minecraft:stone") != 100 and time.time() < deadline:
        time.sleep(0.3)
    assert net_count(term(), "minecraft:stone") == 100
    r = action(kind="terminal", k="minecraft:diamond")
    assert r["ok"] is False, r
    # crafting terminal: planks straight from logs stored in the network
    env.cmd("recipe take @a *")
    debug(op="closeScreen")
    env.cmd("setblock 2 -60 0 toms_storage:crafting_terminal[facing=east]")
    time.sleep(0.5)
    s = env.open(2, -60, 0, lambda s: s["screen"].get("terminal") is True and s["menu"].get("grid") == [3, 3])
    deadline = time.time() + 8
    while True:  # the terminal's item list arrives a moment after it opens
        rs = http("/api/recipes?q=oak%20planks")
        r = next(r for r in rs["recipes"] if r["k"] == "minecraft:oak_planks")
        if r["can"] or time.time() > deadline:
            break
        time.sleep(0.4)
    assert r["can"], "network logs should count as ingredients: %r" % r
    res = action(kind="craft", id=r["id"], max=False)
    assert res["ok"], res
    try:
        s = wait_for(lambda s: count(s, "minecraft:oak_planks") == 4, 5, "planks crafted from the network")
    except AssertionError:
        raise AssertionError("crafting terminal did not craft a locked recipe from the network")
    no_cursor(s)
    assert action(kind="craft", id=r["id"], max=True)["ok"]
    s = wait_for(lambda s: count(s, "minecraft:oak_planks") > 4, 5, "more planks with Max")
    time.sleep(1.5)
    s = settle()
    print(f"  (Max crafted {count(s, 'minecraft:oak_planks') - 4} more planks; network logs left: {net_count(term(), 'minecraft:oak_log')})")
    no_cursor(s)


@test
def battle_helper(env):
    """Cobblemon only: start a wild battle, read both sides, pick a move from the panel."""
    env.reset()
    if "party" not in state():
        print("  (skipped: Cobblemon not loaded)")
        return
    env.cmd("execute as @a run stopbattle @s")
    env.cmd("execute as @a run clearparty @s")
    env.cmd("execute as @a run pokegive @s pikachu level=60")
    wait_for(lambda s: s["party"]["slots"][0].get("name"), 6, "pikachu in party")
    action(kind="party", slot=0)
    env.cmd("kill @e[type=cobblemon:pokemon]")
    # Cobblemon's own battle builder (throwing a Pokémon at a wild one is up to the player)
    debug(op="wildBattle", pokemon="magikarp level=2")
    wait_for(lambda s: s.get("battle") is True, 10, "battle start")
    deadline = time.time() + 10
    b = http("/api/battle")
    while not b.get("request") and time.time() < deadline:
        time.sleep(0.5)
        b = http("/api/battle")
    assert b["active"] and b["request"], b
    foe = b["theirs"][0]
    assert foe["species"].lower() == "magikarp" and [t.lower() for t in foe["types"]] == ["water"], foe
    assert not foe["flat"] and 0 < foe["hp"] <= 1, foe
    me = b["mine"][0]
    assert me["species"].lower() == "pikachu" and me["flat"], me
    moves = b["request"]["moves"]
    assert moves and all(m["name"] and m["maxPp"] > 0 for m in moves), moves
    assert any((m.get("type") or "").lower() == "electric" for m in moves), [m.get("type") for m in moves]
    assert b["request"]["singles"] is True
    bad = action(kind="battle", op="move", id="not-a-move")
    assert bad["ok"] is False, bad
    move = next(m for m in moves if not m["disabled"] and m["pp"] > 0 and m.get("cat") != "status")
    pp_before = move["pp"]
    assert action(kind="battle", op="move", id=move["id"])["ok"]
    # the turn plays out: either the battle ends (Magikarp fainted) or the move's PP went down
    deadline = time.time() + 25
    while time.time() < deadline:
        s = state()
        if not s.get("battle"):
            break
        b2 = http("/api/battle")
        r2 = b2.get("request")
        if r2:
            mv2 = next((m for m in r2["moves"] if m["id"] == move["id"]), None)
            if mv2 and mv2["pp"] < pp_before:
                break
        time.sleep(0.5)
    else:
        raise AssertionError("choosing a move from the panel did not advance the battle")
    env.cmd("execute as @a run stopbattle @s")
    env.cmd("kill @e[type=cobblemon:pokemon]")
    wait_for(lambda s: not s.get("battle"), 8, "battle end")


@test
def spawn_finder(env):
    """Cobblemon only: what can spawn here, with Pokédex status."""
    env.reset()
    if "party" not in state():
        print("  (skipped: Cobblemon not loaded)")
        return
    # The flat test world is stone where we stand: give the spawner grass to work with.
    env.cmd("fill -16 -61 -16 16 -61 16 minecraft:grass_block")
    env.cmd("fill -16 -60 -16 16 -55 16 minecraft:air")
    env.cmd("time set 6000")
    before = http("/api/chat")["lines"]
    r = http("/api/spawns", timeout=20)
    assert r.get("ok"), r
    assert http("/api/chat")["lines"][-5:] == before[-5:], "spawn checks must not show up in chat"
    assert [b["bucket"] for b in r["buckets"]] == ["common", "uncommon", "rare", "ultra-rare"], r["buckets"]
    entries = [e for b in r["buckets"] for e in b["list"]]
    print(f"  ({len(entries)} spawnable here, e.g. {', '.join(e['name'] + ' ' + str(e['pct']) + '%' for e in entries[:4])})")
    if not entries:
        # The peaceful superflat test world spawns nothing; the answer must still be captured cleanly.
        # (Parsing of real lists is covered by SpawnTextTest and was checked in a real world.)
        assert all(b.get("note", "").startswith("Nothing can spawn") for b in r["buckets"]), r["buckets"]
        return
    assert all(":" not in e["name"][-1:] for e in entries), [e["name"] for e in entries]
    for b in r["buckets"]:
        if b["list"]:
            assert abs(sum(e["pct"] for e in b["list"]) - 100) < 2, (b["bucket"], sum(e["pct"] for e in b["list"]))
    resolved = [e for e in entries if e.get("dex")]
    assert len(resolved) >= len(entries) * 0.9, [e["name"] for e in entries if not e.get("dex")]
    assert all(e["dexState"] in ("NONE", "ENCOUNTERED", "CAUGHT") for e in resolved)


@test
def stress_rapid_actions(env):
    """Spam moves like an impatient player; nothing may be lost or left on the cursor."""
    env.reset()
    s = env.chest({i: ("minecraft:stone" if i % 2 else "minecraft:dirt", 1 + i * 2) for i in range(0, 27, 3)})
    before = totals(s)
    import random
    rnd = random.Random(7)
    idx = [x["i"] for x in slots(s, "cph")]
    for _ in range(80):
        a, b = rnd.sample(idx, 2)
        action(kind="inv", op="move", **{"from": a}, to=b, amount=rnd.choice(["all", "half", "one"]), menu=mid(s))
    s = settle()
    assert totals(s) == before, (before, totals(s))
    no_cursor(s)


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("-k", default="", help="only run tests whose name contains this")
    ap.add_argument("--world", default=None, help="open (or create) this throwaway world first")
    args = ap.parse_args()
    BASE = args.base
    env = Env(args.world)
    failed = 0
    for t in TESTS:
        if args.k and args.k not in t.__name__:
            continue
        start = time.time()
        try:
            t(env)
            print(f"PASS {t.__name__} ({time.time() - start:.1f}s)", flush=True)
        except Exception as e:
            failed += 1
            print(f"FAIL {t.__name__}: {e}", flush=True)
            traceback.print_exc(limit=3)
    try:
        debug(op="closeScreen")
        debug(op="endTests")  # lifts the black test overlay right away
    except Exception:
        pass
    print(f"\n{len(TESTS) - failed if not args.k else '?'} passed, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
