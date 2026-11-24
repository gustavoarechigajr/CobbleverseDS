package dev.cvds.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.cvds.CvdsClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * A top-down map of explored terrain for the panel's Map tab, drawn from the chunks the client has
 * loaded (like a vanilla map: block map colours, hill shading, water depth). 256×256-block tiles,
 * one pixel per block, saved as PNG under {@code cvds-map/<world>/<dimension>/}. Surface dimensions only.
 */
public final class MapTiles {
	private MapTiles() {}
	static final int TILE = 256;
	static final int CHUNKS_PER_TICK = 6;
	static final long SAVE_EVERY_MS = 30_000;

	static final class Tile {
		final int[] argb = new int[TILE * TILE]; // 0 = never seen
		volatile int ver = 0;
		volatile boolean dirty = false;
	}

	private static final Map<String, Tile> TILES = new ConcurrentHashMap<>();   // key "dim|tx|tz"
	private static final Set<String> ON_DISK = ConcurrentHashMap.newKeySet();
	private static final LinkedHashSet<Long> QUEUE = new LinkedHashSet<>();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "cvds-map-io"); t.setDaemon(true); return t; });
	private static volatile Path dir;          // folder of the current world
	private static volatile String worldId;
	private static long lastSave = 0, lastNearby = 0;

	static String key(String dim, int tx, int tz) { return dim + "|" + tx + "|" + tz; }
	static String dimId(ClientLevel l) { return l.dimension().location().toString(); }
	static boolean mappable(ClientLevel l) { return l != null && !l.dimensionType().hasCeiling(); }

	// ---------- lifecycle ----------

	public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (!mappable(level)) return;
		synchronized (QUEUE) { QUEUE.add(chunk.getPos().toLong()); }
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) { if (worldId != null) leaveWorld(); return; }
		String id = currentWorldId(mc);
		if (!id.equals(worldId)) enterWorld(mc, id);
		if (!mappable(level)) return;
		long now = System.currentTimeMillis();
		// Blocks change where you are: redraw the chunks around you every 10 s.
		if (now - lastNearby > 10_000) {
			lastNearby = now;
			ChunkPos c = mc.player.chunkPosition();
			synchronized (QUEUE) { for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) QUEUE.add(ChunkPos.asLong(c.x + dx, c.z + dz)); }
		}
		for (int n = 0; n < CHUNKS_PER_TICK; n++) {
			long cp;
			synchronized (QUEUE) {
				Iterator<Long> it = QUEUE.iterator();
				if (!it.hasNext()) break;
				cp = it.next();
				it.remove();
			}
			int cx = ChunkPos.getX(cp), cz = ChunkPos.getZ(cp);
			LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
			if (chunk != null && !chunk.isEmpty()) renderChunk(level, chunk);
		}
		if (now - lastSave > SAVE_EVERY_MS) { lastSave = now; saveDirty(); }
	}

	private static String currentWorldId(Minecraft mc) {
		var sp = mc.getSingleplayerServer();
		String id = sp != null ? "sp-" + sp.getWorldData().getLevelName() : mc.getCurrentServer() != null ? "mp-" + mc.getCurrentServer().ip : "unknown";
		return id.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static void enterWorld(Minecraft mc, String id) {
		if (worldId != null) leaveWorld();
		worldId = id;
		dir = mc.gameDirectory.toPath().resolve("cvds-map").resolve(id);
		ON_DISK.clear();
		try (var dims = Files.list(dir)) {
			dims.filter(Files::isDirectory).forEach(d -> {
				String dim = d.getFileName().toString().replaceFirst("_", ":");
				try (var files = Files.list(d)) {
					files.forEach(f -> {
						String[] p = f.getFileName().toString().replace(".png", "").split("_");
						if (p.length == 2) ON_DISK.add(key(dim, Integer.parseInt(p[0]), Integer.parseInt(p[1])));
					});
				} catch (Exception ignored) {}
			});
		} catch (Exception ignored) {} // no map yet
	}

	private static void leaveWorld() {
		saveDirty();
		TILES.clear();
		ON_DISK.clear();
		synchronized (QUEUE) { QUEUE.clear(); }
		worldId = null;
		dir = null;
	}

	// ---------- drawing ----------

	private static void renderChunk(ClientLevel level, LevelChunk chunk) {
		String dim = dimId(level);
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		int tx = Math.floorDiv(bx, TILE), tz = Math.floorDiv(bz, TILE);
		Tile t = tile(dim, tx, tz, true);
		int ox = Math.floorMod(bx, TILE), oz = Math.floorMod(bz, TILE);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minY = level.getMinBuildHeight();
		boolean changed = false;
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
				if (y < minY) continue;
				int wx = bx + lx, wz = bz + lz;
				pos.set(wx, y, wz);
				BlockState st = chunk.getBlockState(pos);
				// skip see-through tops (glass, leaves keep their colour; plain air/none goes down)
				int guard = 0;
				while (st.getMapColor(level, pos) == MapColor.NONE && y > minY && guard++ < 16) { y--; pos.setY(y); st = chunk.getBlockState(pos); }
				MapColor col = st.getMapColor(level, pos);
				if (col == MapColor.NONE) continue;
				int shade;
				if (!st.getFluidState().isEmpty()) {
					int depth = 0;
					BlockPos.MutableBlockPos d = pos.mutable();
					while (depth < 12 && d.getY() > minY && !chunk.getBlockState(d).getFluidState().isEmpty()) { depth++; d.move(0, -1, 0); }
					double f = depth * 0.1 + ((lx + lz) & 1) * 0.2;
					shade = f < 0.5 ? 255 : f > 0.9 ? 180 : 220;
				} else {
					int north = lz > 0 ? chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz - 1)
						: level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz - 1) - 1;
					shade = y > north ? 255 : y < north ? 180 : 220;
				}
				int rgb = col.col;
				int r = ((rgb >> 16) & 0xFF) * shade / 255, g = ((rgb >> 8) & 0xFF) * shade / 255, b = (rgb & 0xFF) * shade / 255;
				int argb = 0xFF000000 | r << 16 | g << 8 | b;
				int i = (oz + lz) * TILE + (ox + lx);
				if (t.argb[i] != argb) { t.argb[i] = argb; changed = true; }
			}
		}
		if (changed) { t.ver++; t.dirty = true; }
	}

	// ---------- tiles: memory, disk, PNG ----------

	static Tile tile(String dim, int tx, int tz, boolean create) {
		String k = key(dim, tx, tz);
		Tile t = TILES.get(k);
		if (t != null) return t;
		if (ON_DISK.contains(k)) {
			t = load(dim, tx, tz);
			if (t != null) { TILES.put(k, t); return t; }
		}
		if (!create) return null;
		return TILES.computeIfAbsent(k, x -> new Tile());
	}

	private static Path file(String dim, int tx, int tz) {
		return dir.resolve(dim.replace(':', '_')).resolve(tx + "_" + tz + ".png");
	}

	private static Tile load(String dim, int tx, int tz) {
		Path p = dir == null ? null : file(dim, tx, tz);
		if (p == null || !Files.exists(p)) return null;
		try (InputStream in = Files.newInputStream(p); NativeImage img = NativeImage.read(in)) {
			Tile t = new Tile();
			for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) t.argb[z * TILE + x] = abgrToArgb(img.getPixelRGBA(x, z));
			t.ver = 1;
			return t;
		} catch (Exception e) {
			CvdsClient.LOG.warn("cvds: could not read map tile {}", p, e);
			return null;
		}
	}

	static int abgrToArgb(int c) { return (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF); }

	/** PNG bytes for a tile (null if nothing was ever mapped there). */
	public static byte[] png(String dim, int tx, int tz) throws Exception {
		Tile t = tile(dim, tx, tz, false);
		if (t == null) return null;
		int[] copy;
		synchronized (t) { copy = t.argb.clone(); }
		try (NativeImage img = new NativeImage(NativeImage.Format.RGBA, TILE, TILE, false)) {
			for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) img.setPixelRGBA(x, z, abgrToArgb(copy[z * TILE + x])); // same swap both ways
			return img.asByteArray();
		}
	}

	private static void saveDirty() {
		Path base = dir;
		if (base == null) return;
		for (var e : TILES.entrySet()) {
			Tile t = e.getValue();
			if (!t.dirty) continue;
			t.dirty = false;
			String[] k = e.getKey().split("\\|");
			String dim = k[0];
			int tx = Integer.parseInt(k[1]), tz = Integer.parseInt(k[2]);
			IO.submit(() -> {
				try {
					byte[] png = png(dim, tx, tz);
					Path p = base.resolve(dim.replace(':', '_')).resolve(tx + "_" + tz + ".png");
					Files.createDirectories(p.getParent());
					Files.write(p, png);
					ON_DISK.add(e.getKey());
				} catch (Exception ex) {
					CvdsClient.LOG.warn("cvds: could not save map tile", ex);
				}
			});
		}
	}

	// ---------- what the panel asks for ----------

	/** Tiles known for a dimension, as [tx, tz, ver]. */
	static JsonArray tiles(String dim) {
		JsonArray a = new JsonArray();
		Set<String> keys = new HashSet<>(ON_DISK);
		keys.addAll(TILES.keySet());
		for (String k : keys) {
			String[] p = k.split("\\|");
			if (!p[0].equals(dim)) continue;
			Tile t = TILES.get(k);
			JsonArray e = new JsonArray();
			e.add(Integer.parseInt(p[1]));
			e.add(Integer.parseInt(p[2]));
			e.add(t == null ? 1 : t.ver);
			a.add(e);
		}
		return a;
	}

	/** Surface height for a new waypoint (falls back to the player's height where nothing is loaded). */
	static int surfaceY(Minecraft mc, int x, int z) {
		var level = mc.level;
		if (level != null && level.hasChunk(x >> 4, z >> 4)) return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		return mc.player != null ? mc.player.getBlockY() : 64;
	}
}
