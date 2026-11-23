package dev.cvds.mixin;

import dev.cvds.feature.SpawnCapture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Singleplayer only: while the spawn finder runs Cobblemon's /checkspawn, that command's
 * replies (sent to the player, not the command source) are captured instead of shown in chat.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
	private void cvds$capture(Component message, boolean overlay, CallbackInfo ci) {
		if (SpawnCapture.offer(message)) ci.cancel();
	}
}
