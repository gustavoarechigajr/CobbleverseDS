package dev.cvds.mixin;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game's background music waits while the panel's jukebox plays (it would otherwise start on top of it). */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void cvds$quietForJukebox(CallbackInfo ci) {
		if (dev.cvds.feature.Jukebox.suppressMusic) ci.cancel();
	}
}
