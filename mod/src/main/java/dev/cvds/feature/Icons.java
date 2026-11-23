package dev.cvds.feature;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.cvds.CvdsClient;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Renders item icons to PNG off-screen, the same way inventory slots draw them, and caches
 * them by item key. Rendering happens on the client thread at the end of a tick.
 */
public final class Icons {
	private Icons() {}
	static final int SIZE = 64;
	static final int PER_TICK = 8;
	/** ~3-8 KB each, so this bounds the cache to a few tens of MB on very long sessions. */
	static final int MAX_CACHED = 4000;
	private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<byte[]>> PENDING = new ConcurrentHashMap<>();
	private static final Queue<String> QUEUE = new ConcurrentLinkedQueue<>();
	private static TextureTarget target;
	private static boolean broken = false;
	private static int failuresInARow = 0;
	private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();

	/** PNG bytes for an item key, rendering it if needed. Null if the key was never seen. */
	public static byte[] get(String key, long timeoutMs) throws Exception {
		byte[] hit = CACHE.get(key);
		if (hit != null) return hit;
		if (broken || FAILED.contains(key)) return null;
		if (!ItemKeys.SAMPLES.containsKey(key)) return null;
		CompletableFuture<byte[]> f = PENDING.computeIfAbsent(key, k -> {
			QUEUE.add(k);
			return new CompletableFuture<>();
		});
		return f.get(timeoutMs, TimeUnit.MILLISECONDS);
	}

	public static void tick(Minecraft mc) {
		for (int i = 0; i < PER_TICK && !QUEUE.isEmpty(); i++) {
			String key = QUEUE.poll();
			CompletableFuture<byte[]> f = PENDING.remove(key);
			if (f == null) continue;
			try {
				byte[] png = render(mc, ItemKeys.SAMPLES.get(key));
				if (png == null) throw new IllegalStateException("no sample for " + key);
				if (CACHE.size() >= MAX_CACHED) CACHE.clear(); // crude bound; icons re-render on demand
				CACHE.put(key, png);
				failuresInARow = 0;
				f.complete(png);
			} catch (Throwable t) {
				// One odd modded item shouldn't cost every icon; only give up if nothing renders.
				FAILED.add(key);
				if (++failuresInARow >= 10 && !broken) {
					broken = true;
					CvdsClient.LOG.warn("cvds: icon rendering keeps failing, UI will fall back to text", t);
				}
				f.completeExceptionally(t);
			}
		}
	}

	static byte[] render(Minecraft mc, ItemStack stack) throws Exception {
		if (stack == null) return null;
		if (target == null) {
			target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
			target.setClearColor(0, 0, 0, 0);
		}
		target.clear(Minecraft.ON_OSX);
		target.bindWrite(true);
		Matrix4f proj = new Matrix4f().setOrtho(0, 16, 16, 0, 1000, 21000);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		mv.identity();
		mv.translate(0, 0, -11000);
		RenderSystem.applyModelViewMatrix();
		try {
			GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
			g.renderItem(stack, 0, 0);
			g.flush();
			// Read back with alpha (Screenshot.takeScreenshot forces it opaque).
			try (NativeImage img = new NativeImage(SIZE, SIZE, false)) {
				RenderSystem.bindTexture(target.getColorTextureId());
				img.downloadTexture(0, false);
				img.flipY();
				return img.asByteArray();
			}
		} finally {
			mv.popMatrix();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.restoreProjectionMatrix();
			mc.getMainRenderTarget().bindWrite(true);
		}
	}
}
