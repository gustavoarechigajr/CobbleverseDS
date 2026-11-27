# Thor test plan: offline play, icons, moves, battle bag, crafting, jukebox

Hand-off for the Claude instance that has the AYN Thor attached over ADB. Branch: `claude/thor-device-testing-jgfm7i`.
Read `HANDOFF.md` first: deploy steps, the OLED rules and **never touch the real save (`saves/New World`)**.

The owner reported six things after a test night. The game will be played **on a plane**, so offline is the
priority. This branch fixes 2–6 in the mod and panel. Issue 1 is in Zalith Launcher, not in our code: below is
the research and what to try on the device.

| # | Report | Status on this branch |
|---|---|---|
| 1 | With no internet, Zalith doesn't open the game | Researched (Zalith source). **Needs on-device diagnosis**, see §1 |
| 2 | With no internet, the panel shows no icons (items, Pokémon, backpack) | **Root cause found and reproduced**, fixed. Verify on device |
| 3 | Summary says the Pokémon knows no other moves | Root cause found (wrong Cobblemon list), fixed. Verify with Cobblemon |
| 4 | Items in the battle menu flicker | Root cause found (Bag rebuilt every 600 ms), fixed |
| 5 | Crafting is clunky: cards shift after crafting; should open at a crafting table | Implemented |
| 6 | Jukebox with a playlist/queue | Implemented (new Music tab) |

## What was already checked off-device (in the cloud session)
- `./gradlew build` (unit tests) passes. `node tests/ui_test.js` passes, including 19 new checks; the new checks
  fail on the old `app.js`, so they really cover the fixes. `node tests/typechart_test.js` passes.
- Desktop dev client (no Cobblemon, no Tom's; Xvfb, `ALSOFT_DRIVERS=null` for a silent sound device):
  `tests/integration.py --world cvds-flat3`: **31 passed, 0 failed** (Cobblemon and Tom's tests skip there).
- Issue 2 reproduced with the **old** jar: icons requested while the game loads all fail, then every icon is a
  404 for the rest of the session. Same experiment with the new jar: 503 while loading, then all 200.
- Jukebox on the desktop client: a song (C418 - 11, 71 s) ends on its own and the next one starts.
- **Not checked anywhere yet:** anything needing Cobblemon (moves fix, battle bag on the real pack), the APK
  (no Android SDK in the cloud; the APK is **unchanged** on this branch, so no reinstall is needed), real audio,
  Zalith.

## 0. Build and deploy
```bash
cd mod && ./gradlew build && cd ..            # jar: mod/build/libs/cobbleverse-ds-0.2.0.jar (version not bumped)
node tests/ui_test.js && node tests/typechart_test.js
I=/sdcard/Android/data/com.movtery.zalithlauncher.v2/files/.minecraft/versions/Cobbleverse
adb shell ls $I/mods | grep cobbleverse-ds     # remove the old one first: two copies crash the game
adb shell rm $I/mods/cobbleverse-ds-0.2.0.jar
adb push mod/build/libs/cobbleverse-ds-0.2.0.jar $I/mods/
```
The jar name is unchanged, so confirm the new build is the one running: `POST /api/debug {"op":"icons"}` exists only
in this build (an old jar answers `unknown debug op icons`). The panel reloads its page by itself on a new game
launch, so the new UI (Music tab) shows without reopening the app.

Then run the full suite as in `HANDOFF.md` ("Running the integration tests on the Thor"): debug on, `chmod 666`,
`adb forward tcp:25600 tcp:25599`, `python3 -I tests/integration.py --base http://127.0.0.1:25600 --world cvds-test`.
New tests: `icons_ready_and_healthy`, `crafting_table_keeps_shown_recipes`, `pokemon_page_lists_swappable_moves`,
`jukebox_queue_and_play`. On the Thor, `pokemon_page_lists_swappable_moves` and the battle tests run for real.
`jukebox_queue_and_play` plays sound for ~5 s, then puts the player's saved queue back. It fails with "volume is 0" if
the Jukebox/Note Blocks slider is at 0; set it in Options rather than editing the test.

Afterwards: debug back to `false` (and `chmod 666`), force-stop Zalith and the panel, sleep the device (OLED care).

---

## 1. Zalith doesn't open the game without internet

### What Zalith 2 does between "Launch" and the game starting
Read from ZalithLauncher2's source (main branch, `GameLaunchFlow.kt`, `AccountsManager.kt`, `NetWorkUtils.kt`,
`MinecraftDownloader.kt`, `VersionConfig.kt`). The installed version is 2.8.2 (tag exists; not diffed against main).

1. **Network check:** `hasNetwork = isNetworkAvailable(context)`. If there is no network, a Microsoft account is
   launched as an *offline login* (`version.offlineAccountLogin = true`). So a Microsoft account by itself does **not**
   block an offline launch.
   - `isNetworkAvailable` only checks that the active network is Wi-Fi, cellular or Ethernet. It does **not** check
     that the network reaches the internet (no `NET_CAPABILITY_VALIDATED`). **Plane Wi-Fi (connected, captive portal,
     no internet) counts as "network available"**, and so does home Wi-Fi with the router's internet down.
2. **Account check (only when `hasNetwork`):** if the session wasn't validated yet or the token is within 5 minutes
   of expiry, it validates or refreshes the Microsoft token. On failure it calls `onRefreshFailed` /
   `onReloginRequired`. The strings include `account_refresh_failed_skip` = **"Skip Refresh"** ("Unable to reach the
   authentication server. You can skip the refresh and launch with the current credentials…"), so there is likely a
   dialog with a Skip Refresh button. Not confirmed which dialog appears.
3. **Environment check:** `checkMods`, renderer support, and possibly a **library download**
   (`GameLibDownloader`, when lwjgl3ify patching applies). That download is not gated by `hasNetwork`.
4. **Game integrity check:** unless the version (or global) setting **skip game integrity check** is on, it runs
   `MinecraftDownloader` in `VERIFY_AND_REPAIR` mode over the client jar, every library and every asset, with
   per-file download timeouts of **3 minutes** (5 minutes with several sources). Failure shows
   "下载 Minecraft 过程中出现异常" (*minecraft_download_failed*). Whether it requests anything when every file is
   already present and valid wasn't confirmed (`DownloadEngine` not read). It reads the version JSON locally;
   the asset index may or may not be fetched.
5. `runGame`.

**Most likely culprits:** (a) the integrity check (step 4) trying to reach Mojang's servers, failing or waiting
out the timeouts; (b) on plane or captive Wi-Fi, the account refresh (step 2) waiting for a server it can't reach.
A third possibility is outside Zalith: a mod in the pack doing a blocking network call at startup. That would show
as the game starting (log activity) and then hanging.

### What to do on the Thor (diagnose first, change settings only with the owner's OK)
Record the exact Zalith screen or error for each case. Screenshot the top screen with `tools/thor.sh`.
1. **Find the Zalith settings:** per-version settings for Cobbleverse (Versions → Cobbleverse → settings) and the
   global game settings. Look for the integrity-check toggle (field `skipGameIntegrityCheck`: per version it is
   "Follow Global" / "Enable" / "Disable"; English label not found, probably "Skip game integrity check" or similar).
   Note its current value. **Don't change it without asking the owner.** It's their launcher, and skipping the check
   is safe only while the files are complete.
2. **Baseline online:** launch once with internet so the account is fresh and the files verified. Note how long
   the "verifying / 验证并补全游戏文件" stage takes.
3. **True offline** (airplane mode, Wi-Fi **off**): launch. Expected from the code: the account becomes an offline
   login, and the integrity check is the remaining risk. Note the result and how long it takes.
4. **Wi-Fi with no internet** (simulates plane Wi-Fi): airplane mode plus Wi-Fi on, joined to a network without
   internet, e.g. a phone hotspot with mobile data off. Launch. Expect the account refresh to fail: is there a
   "Skip Refresh" button, and does the game start after it?
5. If 3 or 4 fail at the integrity step: **with the owner's OK**, turn on "skip game integrity check" for the
   Cobbleverse version only, then repeat 3 and 4.
6. If the game process starts but hangs: pull `$I/logs/latest.log` and look at its last lines and for
   `UnknownHostException`, `ConnectException`, `SocketTimeoutException` and `Timed out` (mods with update checks or
   online features). Also pull Zalith's own launcher log (find it under
   `/sdcard/Android/data/com.movtery.zalithlauncher.v2/files/`, e.g. `find … -iname '*log*' -mmin -30`).
7. **Player identity check (important):** in an offline launch, does the player keep the same UUID? If Zalith's
   offline login used a name-based offline UUID, the player would turn up in their world as a **new player**
   (empty inventory and party). The old data isn't lost, just not loaded. Check `latest.log` ("Setting user: …")
   online vs offline, and test on a **copy** of the world (`cp -r 'saves/New World' 'saves/zz-offline-test'`,
   delete it afterwards), never the real save. Do the inventory and party look the same offline?
8. Report to the owner: which case fails, the exact step and message, and the recommended setting(s) for the
   flight. Likely advice: launch once online before the flight; switch on airplane mode **before** launching
   (don't connect to plane Wi-Fi first, or turn Wi-Fi off before launching); skip the integrity check if needed.

Sources: <https://github.com/ZalithLauncher/ZalithLauncher2> (`ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/launch/GameLaunchFlow.kt`,
`game/account/AccountsManager.kt`, `utils/network/NetWorkUtils.kt`, `game/version/download/MinecraftDownloader.kt`,
`game/version/installed/VersionConfig.kt`), issue #1049 (offline accounts are region-limited since 2.4.0).

---

## 2. No icons ("offline")
**Cause (reproduced):** the mod's server, and so the panel, comes up while the game is still loading its resources.
The panel asks for icons right away (the tab icons on page load; the panel also **reloads itself when the game
restarts under it**). Drawing an item before the models load throws (`NullPointerException … BakedModel.getOverrides()`
in `ItemRenderer.getModel`). The old `Icons` marked each such key as failed for good, and after 10 failures switched
**all** icons off until the game was restarted. Offline nights involve several launch attempts with the panel
already open, which is exactly this case. Internet itself isn't involved: nothing in the mod or panel uses it.

**Fix:** `Icons` draws only once `mc.getOverlay() == null` (loading finished), answers **503** meanwhile, forgets
everything after each resource reload (F3+T, pack changes), never caches a blank render and logs the first error.
The panel retries a failed icon for ~5 minutes (`iconErr` in `app.js`) instead of dropping it on the first error.

**On the Thor:**
1. Panel open on the bottom screen, game **not** running. Launch the game (offline, if §1 allows it). While it
   loads, the panel shows the plain tab symbols; when the title screen appears, the item icons fill in within
   seconds. No restart needed.
2. In a world: Storage grid, Party sprites, Storage's Backpack shortcut key and battle Bag all show icons.
3. `curl -H 'X-CVDS: 1' -d '{"op":"icons"}' http://127.0.0.1:25600/api/debug`: expect `"ready":true,"broken":false`
   and `failed` 0 or close to it. `lastError` names an item that can't be drawn, if any. (Debug on.)
4. `grep -n "cvds" latest.log`: no "icon rendering keeps failing".
5. Repeat 1–2 with airplane mode on, to close out the original report.

## 3. "Doesn't know other moves"
**Cause:** the Pokémon page listed `Pokemon.getBenchedMoves()`, only the moves it once knew and forgot, which is
empty for most Pokémon. Cobblemon's Summary swap screen uses `getRelearnableMoves()`: level-up moves up to its level,
evolution moves and benched moves, minus the current four. The server's `BenchMoveHandler` accepts anything in
`getAllAccessibleMoves()`. Checked in the Cobblemon 1.7.3 jar with `javap`.
**Fix:** `PokemonPage.json` sends `getRelearnableMoves()` minus current moves, sorted by name.
**On the Thor:** `integration.py -k pokemon_page` (a level 40 Pikachu: lists moves, swaps one, the old one becomes
swappable). By hand: Party → a Pokémon → Moves → Change: the list matches the in-game Summary's swap list, and
picking one replaces the move. Check the list against the game's Summary (Moves → swap) for one or two Pokémon.

## 4. Battle menu flicker
**Cause:** the Battle tab refreshes every 600 ms, and `renderBag()` replaced the Bag/Poké Balls HTML (`innerHTML`)
each time, so every item icon was re-created and redrawn twice a second. The Pokémon page also rebuilt its moves
and evolution banner every 2.5 s.
**Fix:** both use `setHtml` (rebuilds only when the content changed).
**On the Thor:** start a wild battle (`HANDOFF.md`: pokespawn, then send out; or debug `wildBattle`). Open Bag, then
Poké Balls. Icons and catch-chance chips stay perfectly still; tapping an item still opens its targets. Watch for
10 s. If anything still flickers, note which element: the HP bars and sprites at the top (`#b-sides`) still rebuild
when HP changes, by design.

## 5. Crafting
**Changes:** opening a crafting table switches the panel to **Craft** (a chest still opens Storage; Tom's crafting
terminal stays on Storage), and closing it goes back to the tab you were on. While the grid stays open, cards keep
their order. A recipe you can no longer make stays in place, greyed out with disabled buttons (the server lists it
via `keep=`). Newly makeable ones are added at the end. The order resets when the search text changes or another
grid opens. A craft locks the buttons until it finishes, so a double tap can't craft twice.
**On the Thor:**
1. With planks and sticks, open a crafting table with the controller: the panel jumps to Craft.
2. Craft 1 of something you can make only once: the card stays where it was (greyed); nothing below it moves.
   Something newly makeable appears at the bottom.
3. Tap quickly several times on one card: exactly one craft per finished tap, and no other recipe is ever
   crafted by mistake.
4. Close the table: back to the previous tab. Open a chest: Storage as before.
5. `integration.py -k craft`.

## 6. Jukebox (Music tab)
Plays any jukebox song the pack registers, vanilla discs plus any the pack adds. It plays straight to the player
like background music (no block, nothing changes in the world), on the **Jukebox/Note Blocks** volume. The game's
background music holds while it plays (`MusicManagerMixin`). Left: song list, with **All** / **Discs I have**
(inventory) filter, tap = queue, ▶ = play now, "Queue all shown". Right: queue, tap = play from there, ▲▼ reorder,
✕ remove; Repeat off/all/one; Shuffle; "Clear queue" (two taps). Top: now playing with progress, ⏮ (restarts the song
after 3 s), ▶/■, ⏭. The queue and repeat mode are saved in `config/cvds-jukebox.json`.
**On the Thor:** (keep the volume moderate)
1. `integration.py -k jukebox` (plays ~5 s; restores the saved queue).
2. By hand: queue 2–3 songs, play. Sound comes from the game, with no background-music overlap. Skip, previous,
   reorder and remove while playing. Let a short one end: C418 - 11 is 1:11.
3. Pause the game (Esc): the song pauses and the progress stops; resume continues.
4. Leave the world: the music stops. Rejoin: the queue is still there (stopped).
5. Restart the game: the queue is still there.
6. **Battle:** start a wild battle while a song plays. Does Cobblemon's battle music play on top? Report what
   happens. A follow-up could pause the jukebox during battles if it sounds bad.
7. Volume slider for Jukebox/Note Blocks at 0, then play: the panel shows an error instead of looping.
8. Check the tab rail: 10 tabs, each still easy to hit (~90 px tall).

## Reporting back
For each section: pass or fail, screenshots of anything wrong (`tools/thor.sh` for both screens), and the exact
error text. Commit any test fixes to this branch. For §1, write down which Zalith settings were needed, if any,
so the owner can set them up before the flight.

---

## Results on the Thor (2026-11-27)
Branch build `93d1869` as `cobbleverse-ds-0.2.0.jar`, panel app 0.2.0 unchanged. Run in a fresh throwaway world
`cvds-it` (the suite runs `clear @a`; the owner plays in `cvds-test`), debug on for the run, off afterwards.

| # | Result |
|---|---|
| 1 | **Not run.** ADB is wireless: airplane mode or Wi-Fi off cuts the connection. Owner to try by hand (steps in §1). |
| 2 | **Pass.** Panel open before launch: 11 icon requests answered 503 while loading, all 11 drawn as soon as the title screen appeared (0 failed, 0 blank). All 10 tab icons and in-world icons show. |
| 3 | **Pass.** Lv 40 Pikachu: Change lists 16 moves (Charm … Thunder Wave), in one big-button sheet (the sheet scrolls if longer). Integration swap test passes. Not compared side by side with the in-game Summary swap list. |
| 4 | **Pass.** Wild Snorlax battle: Bag and Poké Balls views pixel-identical across 5 screenshots over 10 s (catch chips 12% / 13%). |
| 5 | **Pass.** Opening a table switches to Craft; triple-tap Craft 1 with 4 planks made exactly one crafting table; all cards kept their places, greyed with "0/4"; closing went back to Storage. |
| 6 | **Pass** (UI and state). Queue 2 songs, play (progress counts), skip, stop, clear queue. Not judged by ear: sound quality and whether Cobblemon battle music plays on top (6.6). |

Integration suite: **31 passed, 0 failed** (all four new tests included, Cobblemon and Tom's tests ran for real).

Seen in passing: when the game was launched from Zalith with the panel already open, the bottom screen's home
launcher came to the front over the panel; the app icon brought it back. Not new to this branch.
