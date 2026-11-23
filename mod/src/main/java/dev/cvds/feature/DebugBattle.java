package dev.cvds.feature;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.battles.BattleBuilder;
import net.minecraft.server.MinecraftServer;

/** Test hook: starts a wild battle on the integrated server (Cobblemon only, debug only). */
final class DebugBattle {
	private DebugBattle() {}

	static void wildBattle(MinecraftServer server, String properties) {
		server.execute(() -> {
			var player = server.getPlayerList().getPlayers().get(0);
			var entity = PokemonProperties.Companion.parse(properties).createEntity(player.serverLevel());
			entity.moveTo(player.getX(), player.getY(), player.getZ() + 3, 180f, 0f);
			player.serverLevel().addFreshEntity(entity);
			BattleBuilder.INSTANCE.pve(player, entity);
		});
	}
}
