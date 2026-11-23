package dev.cvds.feature;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Test hooks, only reachable with debug enabled (-Dcvds.debug=true). */
final class Debug {
	private Debug() {}

	static JsonObject run(Minecraft mc, JsonObject a) {
		String op = a.get("op").getAsString();
		switch (op) {
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
			case "closeScreen" -> { if (mc.player != null) mc.player.closeContainer(); else mc.setScreen(null); }
			case "openWorld" -> {
				if (mc.level != null) return Api.error("already in a world");
				mc.createWorldOpenFlows().openWorld(a.get("name").getAsString(), () -> {});
			}
			case "info" -> {
				JsonObject r = Api.ok();
				r.addProperty("player", mc.player == null ? "" : mc.player.getGameProfile().getName());
				r.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getName());
				return r;
			}
			default -> { return Api.error("unknown debug op " + op); }
		}
		return Api.ok();
	}
}
