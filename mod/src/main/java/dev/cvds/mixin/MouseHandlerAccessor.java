package dev.cvds.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The game's own mouse callbacks, so the panel's touchpad drives any screen like a real mouse. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Invoker("onMove") void cvds$onMove(long window, double x, double y);
	@Invoker("onPress") void cvds$onPress(long window, int button, int action, int mods);
	@Invoker("onScroll") void cvds$onScroll(long window, double xOffset, double yOffset);
}
