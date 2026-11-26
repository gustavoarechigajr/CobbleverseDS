package dev.cvds.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.cvds.feature.Debug;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blacks out the whole frame while automated tests drive the game (OLED burn-in protection). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void cvds$blackout(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
		dev.cvds.feature.Mirror.capture(Minecraft.getInstance()); // before the debug blackout covers the frame
		dev.cvds.feature.Pointer.afterFrame(); // mirror clicks that were waiting for the mouse move to be drawn
		if (!Debug.blackout()) return;
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		// Set up the GUI projection ourselves: by now the HUD pass has popped its matrices.
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		mv.identity();
		mv.translate(0, 0, -11000);
		RenderSystem.applyModelViewMatrix();
		RenderSystem.disableDepthTest();
		GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
		g.fill(-10, -10, w + 10, h + 10, 0xFF000000);
		g.flush();
		RenderSystem.enableDepthTest();
		mv.popMatrix();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.restoreProjectionMatrix();
	}

	/** Draws on top of the finished frame in GUI coordinates (the HUD pass has popped its matrices). */
	private static void cvds$overlay(Minecraft mc, java.util.function.Consumer<GuiGraphics> draw) {
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		mv.identity();
		mv.translate(0, 0, -11000);
		RenderSystem.applyModelViewMatrix();
		RenderSystem.disableDepthTest();
		GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
		draw.accept(g);
		g.flush();
		RenderSystem.enableDepthTest();
		mv.popMatrix();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.restoreProjectionMatrix();
	}
}
