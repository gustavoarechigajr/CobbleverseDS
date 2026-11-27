# Handoff: continuing Cobbleverse DS on another PC

Everything needed to build, test and deploy, plus the hard-won gotchas. Start with `README.md` for what the
mod does; this file is about working on it.

## Repo layout
| Path | What |
|---|---|
| `mod/` | Fabric 1.21.1 client mod `cvds` (Java 21, Mojang mappings). `src/main/java/dev/cvds/feature/*` = features, `net/MiniHttpServer` = tiny HTTP server, `inv/` = pure, unit-tested logic (click planner, spawn text parser). |
| `mod/src/main/resources/assets/cvds/web/` | The touch UI (`index.html`, `app.js`, `app.css`, `fonts/`). Served by the mod at `http://127.0.0.1:25599/`. |
| `android/` | WebView wrapper app for the bottom screen (`dev.cvds.app`), built by `android/build.sh` without Gradle. |
| `tests/integration.py` | End-to-end tests against a running game (31 tests). |
| `tests/ui_test.js` | jsdom UI test against a fake server; `tests/typechart_test.js`; `tests/mock_server.py` (sample-data server for UI design); `tests/perf.py` (FPS sampling). |
| `tools/thor.sh` | ADB helper (screenshots of each screen, taps, keys). `tools/worldexport/` = the jar used once to export a world out of PojavLauncher. |

## Prerequisites
- **JDK 21** for the mod (Gradle wrapper 8.14.3, Loom 1.11.8; Loom 1.19+ needs Java 25). Set `JAVA_HOME`.
- **Android SDK** with build-tools 34+ and a platform 33+, plus any JDK 17+, for the APK. `build.sh` finds it in
  `$ANDROID_SDK`, `~/android-dev/sdk` or `~/Android/Sdk`. A debug key is created on first build if `~/.android/debug.keystore` is missing.
  Note: a different PC means a different debug key, so `adb install -r` over the Deck-built app fails; uninstall it once first.
- **adb**, **python3**, **node** (+ `cd tests && npm install` for the UI test).

## Build
```bash
cd mod && ./gradlew build            # jar -> mod/build/libs/cobbleverse-ds-<mod_version>.jar (also runs unit tests)
android/build.sh                     # apk -> android/build/cobbleverse-ds.apk
```
Cobblemon and Tom's Simple Storage are compile-only dependencies pulled from Modrinth's Maven (the exact builds
in the pack, matched by checksum).

## Tests
- **Unit:** `./gradlew test` (HTTP parsing, click planner with 6000 fuzz rounds, spawn text).
- **UI:** `node tests/ui_test.js`, `node tests/typechart_test.js`.
- **Desktop integration (no Cobblemon/Tom's, those tests skip):** `cd mod && ./gradlew runClient` (debug is on in the
  dev run), then `python3 -I tests/integration.py --world cvds-flat3`. It creates a flat throwaway world.
  Cobblemon can't run in the Loom dev client (it looks up obfuscated class names), so party/PC/battle/spawn
  tests only run on the device.
- **On the Thor (everything):** see below.

## Deploying to the Thor (Zalith Launcher 2)
- Instance folder: `/sdcard/Android/data/com.movtery.zalithlauncher.v2/files/.minecraft/versions/Cobbleverse/`
  (version isolation on, so `mods/`, `config/`, `saves/` live there).
- `adb push mod/build/libs/cobbleverse-ds-<version>.jar <instance>/mods/` (remove an older `cobbleverse-ds-*.jar` there first:
  two copies of the mod crash the game) and `adb install -r android/build/cobbleverse-ds.apk`.
- Wireless ADB: the serial and port change whenever wireless debugging restarts (`adb mdns services`).
  Android turns wireless debugging off after a while asleep; it has to be re-enabled on the device.
- Open the panel: the "Cobbleverse DS" icon (`LaunchActivity`, an invisible trampoline that starts the panel on the
  presentation display), or `adb shell am start -n dev.cvds.app/.LaunchActivity`.
- **Before tapping the bottom screen over ADB, screenshot it.** A game restart can leave the panel closed, and blind
  taps then hit the home screen (once opened Spotify over the game).
- A new debug key (different PC) means `adb uninstall dev.cvds.app` first; that wipes the panel's localStorage.
- `android/build.sh` picks the newest build-tools and platform under `$ANDROID_SDK`, `~/android-dev/sdk` or
  `~/Android/Sdk`, and uses `$JAVA_HOME` or the `java` on PATH.

### Running the integration tests on the Thor
1. Debug on: push `config/cvds.json` with `{"port": 25599, "debug": true}` **and `chmod 666` it** (a file pushed
   by adb is otherwise unreadable to the game: AccessDenied, silently falls back to defaults).
2. Launch the game (Zalith → Launch), `adb forward tcp:25600 tcp:25599`.
3. `python3 -I tests/integration.py --base http://127.0.0.1:25600 --world cvds-test`
   (`cvds-test` is a throwaway superflat world inside the instance; it is created if missing).
4. Afterwards: debug back to `false` (and chmod 666), `am force-stop com.movtery.zalithlauncher.v2`,
   `am force-stop dev.cvds.app`, `input keyevent 223` (sleep).

**OLED care (the owner cares a lot about this):** never leave the game or panel idling on screen. Tests black out
the game frame automatically (debug requests keep it black for 60 s); close everything and sleep the device as
soon as a run is done. Don't loop `KEYCODE_WAKEUP`.

**Never touch the real save** (`saves/New World`). For anything that needs real terrain, copy it
(`cp -r 'New World' zz-something` on the device), use the copy, delete it after (it shows up as a second
"New World" in the world list otherwise).

## Releasing
1. Bump `mod_version` in `mod/gradle.properties` and `versionCode`/`versionName` in `android/AndroidManifest.xml`.
2. `cd mod && ./gradlew build`, `node tests/ui_test.js`, `bash android/build.sh`.
3. Tag `v<version>`, push, and `gh release create v<version>` with the jar and the APK (renamed
   `cobbleverse-ds-<version>.apk`) attached. The APK is signed with the building PC's debug key.

## Gotchas (each cost real debugging time)
- **Controlify must be 2.x** (2.5.2 installed): the pack's Zoomify crashes the game with Controlify 3.x.
- **Recipe book:** Cobbleverse never unlocks recipe-book entries server-side, so the vanilla place-recipe packet
  is silently ignored. Crafting places ingredients with normal clicks (`Crafting.place`).
- **Tom's terminals:** block `facing` points *at* the inventory it reads. "Pull one into inventory" fills the offhand
  first (the mod moves it back). The crafting terminal is filled with Tom's own `{"fill": recipeId}` message (its
  JEI/EMI path), and the result is clicked only once the grid shows that recipe's output.
- **Cobblemon battles:** `MoveActionResponse` needs a target PNX from `MoveTarget.getTargetList()`; null is rejected
  ("Invalid action choice"). Double battles are info-only for now.
- **Cobblemon `/checkspawn`** replies to the *player*, not the command source: `ServerPlayerMixin` captures them while
  the spawn finder runs (otherwise they'd spam chat). Output format is `Name: 12.5%`.
- **Cobblemon commands** that need a player: run them as `execute as @a run ...` from tests.
- **World loading from the mod:** never inside `mc.submit` (deadlock), only from a client tick, only from the title
  screen, and never while a world is loading (session-lock crash).
- **State stability:** anything in `/api/state` that changes every tick (memory, exact time) makes every poll
  "new": constant UI redraws and slow tests. Time is rounded; memory is only in debug `info`.
- **Item keys** leave out durability (it's per slot as `d`), otherwise every damage point is a new key and icon.
- **Security:** POSTs need header `X-CVDS: 1`; Host/Origin must be localhost. Use `curl -H 'X-CVDS: 1'`.
- **Class names** in production are intermediary (`class_490`); don't assert on them in tests.
- **The panel is Android WebView 109** (Chrome 109): no CSS `color-mix()`, no `:has()` assumptions; the desktop preview
  won't catch it. Size rules for the 1240×1080 / ~320 dpi screen are at the top of `app.css` (26 px text, 80 px targets).
- **`setHtml` caches**: clear an element with `setHtml(sel, '')`, never `innerHTML = ''`, or the next identical render
  is skipped (this blanked the battle moves/Switch after every turn).
- **Sophisticated Backpacks (Fabric port)**: read via reflection in `Backpacks.java`; its handler's `getSlots()` is a
  *list*, use `getSlotCount()`. Worn (Accessories) backpacks are covered by `PlayerInventoryProvider.runOnBackpacks`.
- **Creative mode** doesn't consume thrown balls or used items; a "duplicate" in a creative test world is expected.
- **Zalith touch is a trackpad**: a tap clicks where the cursor *is*. Move it with `input -d 0 swipe` (≈1:1), check the
  hover highlight in a screenshot, then tap. Zalith's on-screen "E" button covers the world list's Back button; use
  `input keyevent KEYCODE_ESCAPE`. With debug off the game doesn't auto-join: Singleplayer → select → `KEYCODE_ENTER`.
- **Starting a wild battle without debug**: `/spawnpokemonat X Y Z magikarp level=2` in front, swipe the view onto it,
  then press `key.cobblemon.throwpartypokemon` with a Pokémon out.
- `/save-all` doesn't exist in singleplayer: don't force-stop the game expecting a save (autosave is every 5 min).
  Pause with `input -d 0 keyevent 111`, then tap Save and Quit through the mirror (`pointer tap u 0.5 v 0.673`).
- **Android blocks activity starts from `onCreate`** of an activity not yet on screen (background-launch rules):
  the panel's old "relaunch myself on display 4" silently did nothing. Hence the `LaunchActivity` trampoline.
- **Mirror clicks wait one drawn frame after the move**: Xaero's map only reads the mouse position while rendering,
  so a same-frame click acted on the previous spot (`Pointer` queue + `GameRendererMixin`).
- **A sheet opened on finger-lift gets the browser's own click for that touch**, which closed it at once.
  `sheet()` ignores trusted clicks for 350 ms. jsdom tests can't see this; tap with CDP `Input.dispatchTouchEvent`.
- **Map tile URLs carry the launch id** (`&b=`): tile versions restart each launch, and the WebView otherwise reused a
  cached picture from a previous launch with the same `?v=`.
- **Panel reloads on a new game launch** (`boot` in `/api/state`), so pushed panel files take effect without reopening.
- **Haptics:** the app's `CVDSApp.haptic()` works, but the Thor plays touch vibrations at 0 strength (system setting).
- **Catch chance** runs Cobblemon's configured capture calculator 1200 times on a ball entity that is never added to
  the world, cached per ball/target/HP/status. Test battles: `pokespawn` 3 blocks ahead at pitch 20, then send out.
- **Panel tap on the status bar's right side cycles the idle-dark delay**; wake the panel by tapping the middle.
- **Icons before the game has loaded:** the mod's server (and so the panel) is up long before item models are. Drawing
  then throws (NPE in `ItemRenderer.getModel`); the old code cached that and after 10 failures switched every icon
  off for the session (the "no icons offline" report). `Icons` now waits for `mc.getOverlay() == null`, answers 503
  meanwhile, resets on every resource reload and never caches a blank render; the panel retries failed icons
  (`iconErr`) for ~5 min. `/api/debug {"op":"icons"}` shows the renderer's state.
- **Swappable moves** are `Pokemon.getRelearnableMoves()` (what the Summary's swap screen lists; the server's
  `BenchMoveHandler` accepts anything in `getAllAccessibleMoves()`). `getBenchedMoves()` alone is usually empty.
- **Jukebox** (`Jukebox.java`): a non-positional `SimpleSoundInstance` on the RECORDS source; a song has ended when
  `SoundManager.isActive` turns false (counted in unpaused ticks). Volume 0 or no sound device = never active, so it
  stops after a few tries with an error. `MusicManagerMixin` holds background music while it plays. Queue saved in
  `config/cvds-jukebox.json`. The desktop dev client has sound only with `ALSOFT_DRIVERS=null` on a box without audio.

## Status (2026-11-26)
Done and tested on the Thor: storage panel (any container, tap-tap moves, sort, bulk), crafting (2×2, table, Tom's
crafting terminal from the network), keybind board, party (icons, reorder, send out), Cobblemon PC (boxes,
move/swap/release/sort, search, IVs), Tom's storage terminal, battle helper (singles), spawn finder (singleplayer),
chat, status bar with FPS, OLED care (idle blackout, pixel shift), Pokédex-style design. Since then: battle Bag
(inventory + backpacks) with catch chances, ball throw/equip and gimmicks; Pokémon page (Summary as big buttons);
menu mirror for every other screen; Map tab with waypoints, saved tiles and cave layers; Heal party; panel app 0.2
(placement trampoline, pause when hidden, auto-reload). A panel-side backpack browser was tried and removed: the
owner prefers the Backpack key opening the real backpack screen.

Open items / ideas:
- Verify with real buttons that the controller still reaches the game after opening the panel (the Thor's
  `screen_focus_lock=1` should handle it; injected keys can't test it).
- Double-battle targeting; pasture and CobbleDollars shop screens; drag-to-spread in storage.
- Decided against: importing Xaero's World Map history (fragile undocumented format; Xaero's own map is one tap
  away in the mirror) and a background service that opens/closes the panel with the game (too heavy for one game).
- Zalith's on-screen touch buttons are static on the top screen (burn-in): suggest hiding them in Zalith.

## The Thor setup itself (for reference)
Zalith Launcher 2.8.2, MobileGlues renderer, 6144 MB, Controlify 2.5.2, VSync off / 60 FPS cap, simulation distance 6.
Measured: ~60 FPS standing, 32–45 FPS for ~15 s while new chunks generate. The old PojavLauncher install still has
the original world (`net.kdt.pojavlaunch`).
