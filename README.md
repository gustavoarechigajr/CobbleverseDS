# Cobbleverse DS

A second-screen companion for playing Cobbleverse (Minecraft 1.21.1 + Fabric + Cobblemon) on a
dual-screen handheld such as the AYN Thor. The game runs on the top screen; the bottom screen
becomes a touch panel for the things a controller is bad at.

- `mod/`: Fabric client mod (`cvds`). Serves the touch UI and a small JSON API on
  `http://127.0.0.1:25599/` (loopback only).
- `android/`: a tiny full-screen WebView app that shows that UI on the bottom screen.
- `tests/integration.py`: end-to-end tests that drive a running game through the same API.

## What it does

| Tab | |
|---|---|
| Storage | Shortcut keys open your Sophisticated Backpack, Tom's terminal and Accessories in game. A large touch copy of whatever is open (inventory, chests, Sophisticated backpacks and storage, furnaces, crafting tables, any slot-based modded container), laid out like the real screen. Tap-tap or drag to move (Move, Half or One), long-press to shift-click, put an item in a hotbar slot, drop, take all, store all, store matching, sort the container or the inventory, and search. |
| Network | (Tom's Simple Storage) With a Storage or Crafting Terminal open, the Storage tab adds a searchable list of the whole network with counts. Tap takes a stack and long-press takes one, straight into your inventory (never the offhand). Store by tapping your own items in Quick mode, or use Store all / Store matching. |
| Craft | Opens by itself at a crafting table (and goes back to your tab when you close it). Search recipes for the open grid (2×2 anywhere, 3×3 at a crafting table), see the ingredients, then Craft 1 or Max. Cards keep their places while you craft: a recipe you run out of stays put, greyed out, and newly makeable ones join at the end. Ingredients are placed with normal clicks, so it works even though Cobbleverse never unlocks recipe-book entries. At a Tom's Crafting Terminal the grid is filled from the whole network, and Max keeps crafting while the network has ingredients. |
| Keys | Every keybind from every mod, grouped by mod, plus a favourites row. Tap to press. |
| Party | Cobblemon party with big sprites, HP, level, status, types and held item. Drag to reorder, a Send out / recall key per card. **Heal party** (singleplayer, outside battle) revives, heals and cures everyone with the cheapest item that does the job, from your inventory and backpacks, then lists what it used and what's missing. Tap a Pokémon for its page. |
| Pokémon page | The Summary as big buttons: opens from a Party card, or by itself when Cobblemon's Summary opens in game. Moves (reorder, swap in any move it can relearn, the same list as the Summary, details), Stats (IVs, EVs, friendship, XP), Info (nature, ability, held item, rename). A banner offers waiting evolutions. "Game view" shows the real Summary in the mirror. |
| PC | Cobblemon PC: browse every box with real model icons, see the party alongside, drag (or tap one, then another slot) to move or swap (box↔box, box↔party, party↔party), sort a box, search all boxes (name, type, nature, ability, "shiny"), and view nature, ability, IVs and moves. Release needs two taps and is checked against the exact Pokémon you saw. Changes need the PC open in game (the server only accepts PC actions then); otherwise you browse the last-opened copy. |
| Battle | Opens by itself when a Cobblemon battle starts. Bag: medicine from inventory and backpacks, used on a chosen Pokémon. Poké Balls: throw or equip, each showing its **catch chance** (Cobblemon's own capture maths, including HP, status, level and ball bonuses). Mega, Z-Move, Dynamax and Tera when available. "Move around" hides the battle screen. Shows both sides' active Pokémon (HP, types, status, stat stages). Big move buttons show type, PP, power and accuracy, STAB, and effectiveness against the opponent (×4 to immune). Tap a move to use it, or switch Pokémon. Targets are chosen the way Cobblemon's own screen does it. In double battles it's info only. |
| Spawns | (singleplayer) What can spawn where you stand, by rarity bucket with chances, Pokémon pictures and a Pokédex badge (New / Seen / Caught); "only not caught" filter; refreshes on biome change. It runs Cobblemon's own `/checkspawn` as you (read-only) and keeps its replies out of chat. |
| Map | (needs Xaero's Minimap for waypoints) A top-down map drawn from the chunks you've loaded, saved per world, with nearby Pokémon and waypoints. Tap the map to add a waypoint or teleport; tap a waypoint to centre, teleport or delete. Underground and in the Nether it switches to a **cave map** of the 16-block band you're in (rock dark, floors and water in colour). |
| Music | A jukebox with a queue: every music disc song in the pack (or only the discs you carry), played to you like background music, using the game's Jukebox/Note Blocks volume. Queue songs, reorder, play now, skip, repeat (queue or one song), shuffle. The game's own background music waits while it plays. The queue is saved. |
| Chat | Chat and commands with the Android keyboard, plus saved one-tap commands (☆ saves, long-press removes). |
| Menu mirror | Any menu without a panel page (title screen, world list, Pokédex, mod menus, Xaero's map) appears as a live picture you touch directly: tap = click, hold = right-click, drag, two fingers = scroll, pinch or double-tap = zoom. The picture is sized to the game's 16:9 shape (no black bars), with big Scroll up / Scroll down keys underneath that scroll where you last touched. |

The status bar shows coordinates, biome, day and time, weather, whether you can sleep, health, food and level.

### OLED care
- After 45 s without a touch, the panel goes fully black. A tap wakes it, and that tap isn't treated as an action. Tap the right side of the status bar to change the delay (15 s to 5 min).
- Everything drifts by up to 3 px every minute.
- The "waiting for Minecraft" screen goes black after 20 s.
- The app never forces the screen to stay on.
- During automated tests the mod draws the game frame solid black.

## Install

You need two files from the [latest release](https://github.com/gustavoarechigajr/CobbleverseDS/releases/latest):

| File | What it is | Goes on |
|---|---|---|
| `cobbleverse-ds-<version>.jar` | The Fabric mod. Runs inside the game and serves the panel. | Your Cobbleverse instance's `mods/` folder |
| `cobbleverse-ds-<version>.apk` | The panel app for the bottom screen. | The handheld (installed like any Android app) |

**Requirements:** Cobbleverse for Minecraft 1.21.1 (Fabric, Cobblemon 1.7) running in an Android launcher such as
Zalith Launcher 2, on a dual-screen device like the AYN Thor. Fabric API comes with the pack. Battle bag
and backpack features use Sophisticated Backpacks; waypoints use Xaero's Minimap. Both are optional.

### 1. Add the mod to Cobbleverse
1. Download `cobbleverse-ds-<version>.jar` on the device.
2. Open the instance's mods folder. In Zalith Launcher 2: tap the folder icon (top right), open
   `versions/Cobbleverse/mods/` (or whatever your Cobbleverse instance is called). With version isolation off,
   it's `.minecraft/mods/` instead.
3. Copy the jar into that folder. **When updating, delete the old `cobbleverse-ds-*.jar` first**, so only
   one copy is there.

### 2. Install the panel app
1. Download `cobbleverse-ds-<version>.apk` on the device and open it. Android asks you to allow installs from
   your browser or file manager the first time; allow it.
2. If an older Cobbleverse DS is installed and Android says the update conflicts with it, uninstall the old app
   and install again. This happens when the app was built on a different computer. It resets the panel's
   favourites and settings.

### 3. Play
1. Start Cobbleverse and load a world.
2. Tap the **Cobbleverse DS** icon (the red lid with the blue lens). It always opens on the bottom screen, from
   either screen, and brings the panel back if it's already running.
3. The panel waits for the game by itself. After a game restart it reconnects and reloads on its own.

To check it's working: the three lights in the panel's top-left turn **green** in a world, **yellow** on the
title screen, and **red** if the game isn't running or the mod isn't installed.

### Building it yourself
`cd mod && ./gradlew build` (JDK 21) makes the jar in `mod/build/libs/`. `android/build.sh` makes the APK in
`android/build/` with plain Android SDK tools (any build-tools 34+ and platform 33+, JDK 17+). See `HANDOFF.md`
for the full development setup and testing on a device.

The app's window never takes input focus (except while you type). Launching any app on the bottom screen still makes Android treat the bottom screen as "focused"; the Thor's `screen_focus_lock` (controller focus on the top screen) keeps controller buttons going to the game. If buttons ever stop reaching the game after opening the panel, tap the top screen once.

The local API only accepts requests from its own page: POSTs need the `X-CVDS: 1` header and the Host/Origin must be localhost, so web pages in the phone's browser can't drive the game.

## Design
The panel is styled as a Pokédex, after Cobbleverse's own Pokédex menu art and logo: a red shell, a blue lens, and three LEDs that show the connection (green: connected, yellow: no world loaded, red: game not reachable). Keys use the logo's black-outlined sticker look; slots copy the Cobblemon Interface pack. Fonts: Pixelify Sans and Nunito (SIL OFL, bundled in `web/fonts`). `python3 -I tests/mock_server.py` serves the UI with sample data for design work without a game.

## Tests
- UI: `cd tests && npm install && node ui_test.js` runs the real page in jsdom against a fake server (tab auto-switching, tap-tap moves, battle/PC/terminal panels, idle blackout). `node tests/typechart_test.js` checks the type chart.
- Unit tests: `cd mod && ./gradlew test` (HTTP parsing, and fuzzing of the move and sort planner against a model of vanilla click rules).
- Integration: start the game with debug on (`-Dcvds.debug=true`, or `"debug": true` in
  `config/cvds.json`), then `python3 -I tests/integration.py --world cvds-test`. Use a
  **throwaway** world name: the tests clear the inventory and place blocks. For the Thor, first run
  `adb forward tcp:25600 tcp:25599`, then add `--base http://127.0.0.1:25600`.
  Turn debug off again afterwards.
