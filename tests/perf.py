#!/usr/bin/env python3
"""Samples FPS / memory / loaded chunks in a running game, with and without the touch UI polling.

    python3 -I tests/perf.py --base http://127.0.0.1:25600 --world zz-perf-copy
Use a COPY of a real world: it is opened and left as it was (no commands are run).
"""
import argparse, json, statistics, threading, time, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://127.0.0.1:25599")
ap.add_argument("--world")
ap.add_argument("--settle", type=int, default=45)
ap.add_argument("--phase", type=int, default=40)
args = ap.parse_args()


def get(path, body=None, timeout=10):
    req = urllib.request.Request(args.base + path, data=None if body is None else json.dumps(body).encode(), method="POST" if body else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def info():
    return get("/api/debug", {"op": "info"})


if args.world:
    while True:  # ask once the game is up; the mod refuses while a world is loading
        try:
            if get("/api/state?rev=-1").get("inGame") or get("/api/debug", {"op": "enterWorld", "name": args.world}).get("ok"):
                break
        except OSError:
            pass
        time.sleep(2)
t0 = time.time()
while not get("/api/state?rev=-1").get("inGame"):
    time.sleep(1)
print(f"in world after {time.time() - t0:.0f}s; letting chunks load for {args.settle}s")
time.sleep(args.settle)


def sample(label):
    fps, mem, chunks = [], [], []
    end = time.time() + args.phase
    while time.time() < end:
        i = info()
        fps.append(i["fps"]); mem.append(i["memUsedMb"]); chunks.append(i["chunks"])
        time.sleep(2)
    print(f"{label:<26} fps avg {statistics.mean(fps):5.1f}  min {min(fps):3d}  max {max(fps):3d} | mem {min(mem)}-{max(mem)} MB | chunks {chunks[-1]} | entities {i['entities']}")
    return statistics.mean(fps)


time.sleep(11)  # let any earlier UI polling lapse (the snapshot stops after 10 s without polls)
a = sample("panel idle (no polling)")
stop = False


def ui_poller():  # what the bottom-screen UI does: a long-poll loop on /api/state
    rev = -1
    while not stop:
        try:
            rev = get(f"/api/state?rev={rev}&wait=1500")["rev"]
        except Exception:
            time.sleep(0.5)


th = threading.Thread(target=ui_poller, daemon=True)
th.start()
b = sample("panel open (UI polling)")
stop = True
print(f"panel cost: {a - b:+.1f} fps")
