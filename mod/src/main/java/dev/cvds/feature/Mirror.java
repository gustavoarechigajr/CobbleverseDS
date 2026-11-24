package dev.cvds.feature;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.cvds.CvdsClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

/**
 * The menu mirror: while the panel shows it, the open menu is copied a few times a second into a
 * small off-screen target and sent to the panel as PNG, where taps are sent back as mouse clicks at the
 * same spot. Capture is only done while frames are being asked for and a menu is open.
 */
public final class Mirror {
	private Mirror() {}
	static final long MIN_INTERVAL_MS = 120;   // at most ~8 frames a second
	static final long IDLE_MS = 1500;          // stop capturing when the panel stops asking

	private static volatile long requestedAt = 0;
	private static volatile boolean wantHi = false;
	private static long lastCapture = 0;
	private static final AtomicBoolean encoding = new AtomicBoolean(false);
	private static final ExecutorService ENC = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "cvds-mirror"); t.setDaemon(true); return t; });
	private static TextureTarget lo, hi;

	private static final Object LOCK = new Object();
	private static byte[] png;
	private static long seq = 0;
	private static int frameW, frameH;

	/** Render thread, end of each frame (the menu is in the main target by then). */
	public static void capture(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (mc.screen == null || now - requestedAt > IDLE_MS || now - lastCapture < MIN_INTERVAL_MS) return;
		if (encoding.get()) return; // the previous frame is still being encoded: skip rather than queue up
		lastCapture = now;
		var main = mc.getMainRenderTarget();
		boolean big = wantHi;
		int w = big ? main.width : Math.max(2, main.width / 2), h = big ? main.height : Math.max(2, main.height / 2);
		TextureTarget t = big ? hi : lo;
		if (t == null || t.width != w || t.height != h) {
			if (t != null) t.destroyBuffers();
			t = new TextureTarget(w, h, false, Minecraft.ON_OSX);
			t.setClearColor(0, 0, 0, 1);
			if (big) hi = t; else lo = t;
		}
		NativeImage img = null;
		try {
			// Copy-and-scale the whole frame with a framebuffer blit (blitToScreen's viewport scaling isn't honoured
			// under the Thor's GL→GLES layer: it gave the bottom-left quarter at full size).
			com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
			com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, t.frameBufferId);
			org.lwjgl.opengl.GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, w, h,
				org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT, big ? org.lwjgl.opengl.GL11.GL_NEAREST : org.lwjgl.opengl.GL11.GL_LINEAR);
			com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
			img = new NativeImage(w, h, false);
			RenderSystem.bindTexture(t.getColorTextureId());
			img.downloadTexture(0, true);
			img.flipY();
		} catch (Throwable e) {
			if (img != null) img.close();
			img = null;
			CvdsClient.LOG.warn("cvds: mirror capture failed", e);
		} finally {
			main.bindWrite(true);
		}
		if (img == null) return;
		NativeImage frame = img;
		encoding.set(true);
		ENC.submit(() -> {
			try {
				byte[] bytes = frame.asByteArray();
				synchronized (LOCK) { png = bytes; frameW = w; frameH = h; seq++; LOCK.notifyAll(); }
			} catch (Throwable e) {
				CvdsClient.LOG.warn("cvds: mirror encode failed", e);
			} finally {
				frame.close();
				encoding.set(false);
			}
		});
	}

	/** HTTP thread: the newest frame after {@code after}, waiting up to {@code waitMs}. Null if none. */
	static Object[] await(long after, boolean hiRes, long waitMs) throws InterruptedException {
		requestedAt = System.currentTimeMillis();
		wantHi = hiRes;
		long deadline = requestedAt + waitMs;
		synchronized (LOCK) {
			while (seq <= after) {
				long left = deadline - System.currentTimeMillis();
				if (left <= 0) break;
				LOCK.wait(left);
				requestedAt = System.currentTimeMillis();
			}
			return png == null ? null : new Object[]{png, seq, frameW, frameH};
		}
	}
}
