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
| Craft | Search recipes for the open grid (2×2 anywhere, 3×3 at a crafting table), see the ingredients, then Craft 1 or Max. Ingredients are placed with normal clicks, so it works even though Cobbleverse never unlocks recipe-book entries. |
| Keys | Every keybind from every mod, grouped by mod, plus a favourites row. Tap to press. |
| Party | Cobblemon party with HP, level, status, types and held item. Tap to select, and a button to send out or recall. |
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

The app's window never takes input focus (except while you type), so the controller stays with the game.

## Tests
- Unit tests: `cd mod && ./gradlew test` (HTTP parsing, and fuzzing of the move and sort planner against a model of vanilla click rules).
- Integration: start the game with debug on (`-Dcvds.debug=true`, or `"debug": true` in
  `config/cvds.json`), then `python3 -I tests/integration.py --world cvds-test`. Use a
  **throwaway** world name: the tests clear the inventory and place blocks. For the Thor, first run
  `adb forward tcp:25600 tcp:25599`, then add `--base http://127.0.0.1:25600`.
  Turn debug off again afterwards.
