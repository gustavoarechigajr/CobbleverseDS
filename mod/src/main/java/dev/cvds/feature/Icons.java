package dev.cvds.feature;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.google.gson.JsonObject;
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
	/** Pokémon are 3D models drawn as items: render them big and trim the empty border, so the
	 *  panel can show them large and sharp (at 64 px the creature was ~40 px of a padded square). */
	static final int MON_SIZE = 256;
	static final String MON_PREFIX = "cobblemon:pokemon_model";
	static final int PER_TICK = 8;
	/** ~3-8 KB each, so this bounds the cache to a few tens of MB on very long sessions. */
	static final int MAX_CACHED = 4000;
	private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<byte[]>> PENDING = new ConcurrentHashMap<>();
	private static final Queue<String> QUEUE = new ConcurrentLinkedQueue<>();
	private static final Map<Integer, TextureTarget> TARGETS = new HashMap<>();
	private static volatile boolean broken = false;
	private static int failuresInARow = 0;
	private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();
	/** Rendering is only possible once the game's resources (item models, texture atlases) are loaded. */
	private static volatile boolean ready = false;
	private static int blanksInARow = 0;
	private static volatile long rendered = 0, failed = 0, blanks = 0, resets = 0;
	private static volatile String lastError = "";

	/** The panel can ask before the game can draw anything: it connects as soon as the mod starts. */
	public static final class NotReady extends Exception {
		NotReady() { super("the game is still loading", null, false, false); }
	}

	/**
	 * PNG bytes for an item key, rendering it if needed. Null if the key was never seen (or can't be drawn).
	 * Throws {@link NotReady} while the game is still loading, so the panel retries instead of giving up.
	 */
	public static byte[] get(String key, long timeoutMs) throws Exception {
		byte[] hit = CACHE.get(key);
		if (hit != null) return hit;
		if (broken || FAILED.contains(key)) return null;
		if (!ItemKeys.SAMPLES.containsKey(key) && !ItemKeys.sampleFromId(key)) return null;
		CompletableFuture<byte[]> f = PENDING.computeIfAbsent(key, k -> {
			QUEUE.add(k);
			return new CompletableFuture<>();
		});
		try {
			return f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			if (!ready) throw new NotReady(); // stays queued: drawn as soon as loading finishes
			throw e;
		} catch (ExecutionException e) {
			if (e.getCause() instanceof NotReady nr) throw nr;
			return null; // this one item can't be drawn: the panel shows its name instead
		}
	}

	public static void tick(Minecraft mc) {
		// Startup loading and resource reloads (F3+T, resource packs) run under the loading overlay. Items drawn
		// then come out blank or throw, and before this check that was cached for the whole session (or, after
		// 10 failures, switched every icon off): a panel opened while the game was starting showed no icons at all.
		boolean nowReady = mc.getOverlay() == null && mc.getItemRenderer() != null;
		if (nowReady && !ready) reset(); // resources (re)loaded: anything drawn before may be stale
		ready = nowReady;
		if (!ready) return;
		for (int i = 0; i < PER_TICK && !QUEUE.isEmpty(); i++) {
			String key = QUEUE.poll();
			CompletableFuture<byte[]> f = PENDING.remove(key);
			if (f == null) continue;
			try {
				boolean mon = key.startsWith(MON_PREFIX);
				byte[] png = render(mc, ItemKeys.SAMPLES.get(key), mon);
				if (png == null) throw new IllegalStateException("no sample for " + key);
				if (png.length == 0) {
					// Nothing drawn (all transparent). Don't cache it: answer "not found" now and draw it again
					// next time. Many blanks in a row means drawing itself is broken right now, so wait a bit.
					blanks++;
					if (++blanksInARow == 20) CvdsClient.LOG.warn("cvds: item icons keep coming out blank (last: {})", key);
					f.complete(null);
					continue;
				}
				blanksInARow = 0;
				if (CACHE.size() >= MAX_CACHED) CACHE.clear(); // crude bound; icons re-render on demand
				CACHE.put(key, png);
				failuresInARow = 0;
				rendered++;
				f.complete(png);
			} catch (Throwable t) {
				// One odd modded item shouldn't cost every icon; only give up if nothing renders.
				FAILED.add(key);
				failed++;
				lastError = key + ": " + t;
				if (failuresInARow == 0) CvdsClient.LOG.warn("cvds: could not draw the icon for {}", key, t);
				if (++failuresInARow >= 10 && !broken) {
					broken = true;
					CvdsClient.LOG.warn("cvds: icon rendering keeps failing, UI will fall back to text until resources reload", t);
				}
				f.completeExceptionally(t);
			}
		}
	}

	/** Forgets every drawn and failed icon (after a resource reload). Pending requests stay queued. */
	static void reset() {
		CACHE.clear();
		FAILED.clear();
		broken = false;
		failuresInARow = 0;
		blanksInARow = 0;
		resets++;
	}

	/** Diagnostics for /api/debug {"op":"icons"}: on-device tests check icons work (e.g. offline). */
	static JsonObject stats() {
		JsonObject o = Api.ok();
		o.addProperty("ready", ready);
		o.addProperty("broken", broken);
		o.addProperty("cached", CACHE.size());
		o.addProperty("queued", QUEUE.size());
		o.addProperty("failedKeys", FAILED.size());
		o.addProperty("rendered", rendered);
		o.addProperty("failed", failed);
		o.addProperty("blanks", blanks);
		o.addProperty("resets", resets);
		o.addProperty("lastError", lastError);
		return o;
	}

	static byte[] render(Minecraft mc, ItemStack stack, boolean mon) throws Exception {
		if (stack == null) return null;
		int size = mon ? MON_SIZE : SIZE;
		TextureTarget target = TARGETS.computeIfAbsent(size, n -> {
			TextureTarget t = new TextureTarget(n, n, true, Minecraft.ON_OSX);
			t.setClearColor(0, 0, 0, 0);
			return t;
		});
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
			try (NativeImage img = new NativeImage(size, size, false)) {
				RenderSystem.bindTexture(target.getColorTextureId());
				img.downloadTexture(0, false);
				img.flipY();
				if (blank(img)) return new byte[0];
				if (!mon) return img.asByteArray();
				try (NativeImage trimmed = trim(img)) { return trimmed.asByteArray(); }
			}
		} finally {
			mv.popMatrix();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.restoreProjectionMatrix();
			mc.getMainRenderTarget().bindWrite(true);
		}
	}

	/** True if no pixel is visible (nothing was drawn). */
	static boolean blank(NativeImage img) {
		int w = img.getWidth(), h = img.getHeight();
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				if ((img.getPixelRGBA(x, y) >>> 24) > 8) return false;
		return true;
	}

	/** Crops to the visible pixels plus a small margin, as a square centred on them. */
	static NativeImage trim(NativeImage src) {
		int w = src.getWidth(), h = src.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				if ((src.getPixelRGBA(x, y) >>> 24) > 8) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
		if (x1 < 0) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1; } // nothing drawn: keep the frame
		int side = Math.max(x1 - x0, y1 - y0) + 1;
		side += Math.max(4, side / 16) * 2;
		int ox = (x0 + x1) / 2 - side / 2, oy = (y0 + y1) / 2 - side / 2;
		NativeImage out = new NativeImage(side, side, true);
		for (int y = 0; y < side; y++)
			for (int x = 0; x < side; x++) {
				int sx = ox + x, sy = oy + y;
				out.setPixelRGBA(x, y, sx >= 0 && sy >= 0 && sx < w && sy < h ? src.getPixelRGBA(sx, sy) : 0);
			}
		return out;
	}
}
