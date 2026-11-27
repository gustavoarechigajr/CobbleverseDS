package dev.cvds.feature;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Test hooks, only reachable with debug enabled (-Dcvds.debug=true or "debug": true in config/cvds.json). */
public final class Debug {
	private Debug() {}
	private static volatile String pendingWorld;
	private static volatile long lastRequest = 0;
	/** How long the screen stays black after the last test request. */
	static final long BLACKOUT_MS = 60_000;

	/** True while an automated test run is active, so the game frame is drawn solid black. */
	public static boolean blackout() {
		return System.currentTimeMillis() - lastRequest < BLACKOUT_MS;
	}

	static JsonObject run(Minecraft mc, JsonObject a) {
		String op = a.get("op").getAsString();
		lastRequest = op.equals("endTests") ? 0 : System.currentTimeMillis();
		switch (op) {
			case "endTests" -> {}
			case "cmd" -> {
				var server = mc.getSingleplayerServer();
				if (server == null) return Api.error("not singleplayer");
				String cmd = a.get("cmd").getAsString();
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
			}
			case "use" -> {
				BlockPos p = new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false));
			}
			case "wildBattle" -> {
				var server = mc.getSingleplayerServer();
				if (server == null || !Snapshot.COBBLEMON) return Api.error("needs singleplayer + Cobblemon");
				DebugBattle.wildBattle(server, a.get("pokemon").getAsString());
			}
			case "closeScreen" -> { if (mc.player != null) mc.player.closeContainer(); else mc.setScreen(null); }
			case "enterWorld" -> {
				// World loading runs its own wait loop and deadlocks inside a queued task, so it is
				// deferred to the next client tick.
				if (mc.level != null) return Api.error("already in a world");
				if (pendingWorld != null || busy(mc)) return Api.error("a world is already loading");
				pendingWorld = a.get("name").getAsString();
			}
			case "icons" -> { return Icons.stats(); }
			case "info" -> {
				JsonObject r = Api.ok();
				r.addProperty("player", mc.player == null ? "" : mc.player.getGameProfile().getName());
				r.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getName());
				r.addProperty("fps", mc.getFps());
				Runtime rt = Runtime.getRuntime();
				r.addProperty("memUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
				r.addProperty("entities", mc.level == null ? 0 : mc.level.getEntityCount());
				r.addProperty("chunks", mc.level == null ? 0 : mc.level.getChunkSource().getLoadedChunksCount());
				return r;
			}
			default -> { return Api.error("unknown debug op " + op); }
		}
		return Api.ok();
	}

	static boolean busy(Minecraft mc) {
		var sc = mc.screen;
		return sc instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
			|| sc instanceof net.minecraft.client.gui.screens.ProgressScreen
			|| sc instanceof net.minecraft.client.gui.screens.GenericMessageScreen
			|| sc instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen;
	}

	/** Opens the requested world, creating a flat peaceful one with cheats on if it doesn't exist. */
	public static void tick(Minecraft mc) {
		String name = pendingWorld;
		// Like vanilla quick play: only once startup resource loading has finished, otherwise the
		// world-load wait loop never sees the loading overlay go away.
		if (name == null || mc.level != null || mc.getOverlay() != null) return;
		// Never interrupt a world that is still loading or saving: reopening then fights it for the
		// save's session lock (and crashes).
		if (busy(mc)) return;
		if (!(mc.screen instanceof TitleScreen)) {
			// startup prompts: accessibility onboarding, Controlify calibration, ...
			mc.options.onboardAccessibility = false;
			mc.options.save();
			mc.setScreen(new TitleScreen());
			return;
		}
		pendingWorld = null;
		if (mc.getLevelSource().levelExists(name)) {
			mc.createWorldOpenFlows().openWorld(name, () -> {});
			return;
		}
		var settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1234L, false, false),
			reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
	}
}
