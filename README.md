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
| Storage | A large touch copy of whatever is open (inventory, chests, Sophisticated backpacks and storage, furnaces, crafting tables, any slot-based modded container), laid out like the real screen. Tap-tap to move (Move, Half or One), long-press to shift-click, put an item in a hotbar slot, drop, take all, store all, store matching, sort the container or the inventory, and search. |
| Network | (Tom's Simple Storage) With a Storage or Crafting Terminal open, the Storage tab adds a searchable list of the whole network with counts. Tap takes a stack and long-press takes one, straight into your inventory (never the offhand). Store by tapping your own items in Quick mode, or use Store all / Store matching. |
| Craft | Search recipes for the open grid (2×2 anywhere, 3×3 at a crafting table), see the ingredients, then Craft 1 or Max. Ingredients are placed with normal clicks, so it works even though Cobbleverse never unlocks recipe-book entries. At a Tom's Crafting Terminal the grid is filled from the whole network, and Max keeps crafting while the network has ingredients. |
| Keys | Every keybind from every mod, grouped by mod, plus a favourites row. Tap to press. |
| Party | Cobblemon party with HP, level, status, types and held item. Tap to select, and a button to send out or recall. |
| PC | Cobblemon PC: browse every box with real model icons, see the party alongside, tap one Pokémon then another slot to move or swap (box↔box, box↔party, party↔party), sort a box, search all boxes (name, type, nature, ability, "shiny"), and view nature, ability, IVs and moves. Release needs two taps and is checked against the exact Pokémon you saw. Changes need the PC open in game (the server only accepts PC actions then); otherwise you browse the last-opened copy. |
| Battle | Opens by itself when a Cobblemon battle starts. Shows both sides' active Pokémon (HP, types, status, stat stages). Big move buttons show type, PP, power and accuracy, STAB, and effectiveness against the opponent (×4 to immune). Tap a move to use it, or switch Pokémon. Targets are chosen the way Cobblemon's own screen does it. In double battles it's info only. |
| Spawns | (singleplayer) What can spawn where you stand, by rarity bucket with chances, Pokémon pictures and a Pokédex badge (New / Seen / Caught); "only not caught" filter; refreshes on biome change. It runs Cobblemon's own `/checkspawn` as you (read-only) and keeps its replies out of chat. |
| Chat | Chat and commands with the Android keyboard, plus saved one-tap commands (☆ saves, long-press removes). |

The status bar shows coordinates, biome, day and time, weather, whether you can sleep, health, food and level.

### OLED care
- After 45 s without a touch, the panel goes fully black. A tap wakes it, and that tap isn't treated as an action. Tap the right side of the status bar to change the delay (15 s to 5 min).
- Everything drifts by up to 3 px every minute.
- The "waiting for Minecraft" screen goes black after 20 s.
- The app never forces the screen to stay on.
- During automated tests the mod draws the game frame solid black.

## Install (Thor + Zalith Launcher 2)
1. Build: `cd mod && ./gradlew build` (needs JDK 21), then `android/build.sh`.
2. Copy `mod/build/libs/cobbleverse-ds-*.jar` into the instance's `mods/` folder
   (`.minecraft/versions/Cobbleverse/mods/` in Zalith).
3. Install `android/build/cobbleverse-ds.apk`. Open "Cobbleverse DS" from the bottom screen's
   launcher (opened from the top screen, it moves itself to the bottom screen).

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
