package dev.cvds.feature;

import dev.cvds.CvdsClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * The pack's own music for the jukebox, besides the music discs: every background-music and battle-music
 * file the active resource packs and mods provide (Cobbleverse swaps Minecraft's background tracks for
 * Pokémon music, adds battle themes, LumyMon adds area music). The same files Music Notification's
 * jukebox lists. Scanned off the client thread after each resource load: the top-priority file for each
 * path, with the title, artist and exact length read from its Ogg Vorbis headers where the file has them.
 */
final class PackMusic {
	private PackMusic() {}

	/** A playable file: {@code file} is its sound location (e.g. {@code minecraft:music/game/key}). */
	record Track(String id, ResourceLocation file, String name, String artist, String group, String src, int lenTicks) {}

	private static volatile List<Track> tracks = List.of();
	private static volatile boolean scanned = false;
	private static final AtomicBoolean scanning = new AtomicBoolean(false);
	private static boolean wasLoading = true;

	/** The tracks found so far (empty until the first scan finishes). Starts a scan when needed. */
	static List<Track> tracks(Minecraft mc) {
		boolean loading = mc.getOverlay() != null;
		if (loading) { wasLoading = true; return tracks; }
		if (wasLoading) { wasLoading = false; scanned = false; } // resources (re)loaded: packs may have changed
		if (!scanned && scanning.compareAndSet(false, true)) {
			var rm = mc.getResourceManager();
			Thread t = new Thread(() -> {
				try { tracks = scan(rm); }
				catch (Throwable e) { CvdsClient.LOG.warn("cvds: could not list the pack's music", e); }
				finally { scanned = true; scanning.set(false); }
			}, "cvds-pack-music");
			t.setDaemon(true);
			t.start();
		}
		return tracks;
	}

	static boolean ready() { return scanned; }

	static final int MIN_SONG_TICKS = 20 * 20;

	static boolean isIntro(String path) {
		for (String seg : path.split("/")) if (seg.equals("intro") || seg.endsWith("-intro") || seg.endsWith("_intro")) return true;
		return false;
	}

	/** Game folders in battle-music packs, spelled out. */
	private static final Map<String, String> GAMES = Map.ofEntries(
		Map.entry("rby", "Red, Blue & Yellow"), Map.entry("gsc", "Gold, Silver & Crystal"), Map.entry("rse", "Ruby, Sapphire & Emerald"),
		Map.entry("frlg", "FireRed & LeafGreen"), Map.entry("dppt", "Diamond, Pearl & Platinum"), Map.entry("hgss", "HeartGold & SoulSilver"),
		Map.entry("bw", "Black & White"), Map.entry("b2w2", "Black 2 & White 2"), Map.entry("xy", "X & Y"),
		Map.entry("oras", "Omega Ruby & Alpha Sapphire"), Map.entry("sm", "Sun & Moon"), Map.entry("usum", "Ultra Sun & Ultra Moon"),
		Map.entry("lgpe", "Let's Go"), Map.entry("swsh", "Sword & Shield"), Map.entry("bdsp", "Brilliant Diamond & Shining Pearl"),
		Map.entry("pla", "Legends: Arceus"), Map.entry("sv", "Scarlet & Violet"), Map.entry("pbr", "Battle Revolution"),
		Map.entry("xd", "XD"), Map.entry("colosseum", "Colosseum"), Map.entry("stadium", "Stadium"));

	private static List<Track> scan(net.minecraft.server.packs.resources.ResourceManager rm) {
		Map<ResourceLocation, Resource> found = rm.listResources("sounds", rl -> {
			String p = rl.getPath();
			return p.endsWith(".ogg") && (p.startsWith("sounds/music/") || p.startsWith("sounds/battle/"));
		});
		List<Track> out = new ArrayList<>();
		for (var e : found.entrySet()) {
			ResourceLocation rl = e.getKey();
			String path = rl.getPath().substring("sounds/".length(), rl.getPath().length() - ".ogg".length());
			if (path.startsWith("music/disc") || path.startsWith("records/")) continue; // discs are listed as discs
			ResourceLocation file = ResourceLocation.fromNamespaceAndPath(rl.getNamespace(), path);
			Ogg ogg = new Ogg();
			try (InputStream in = e.getValue().open()) { ogg.read(in); }
			catch (Throwable ignored) {}
			String name = ogg.title != null && !ogg.title.isBlank() ? ogg.title.trim() : pretty(path);
			String group = path.startsWith("battle/") || path.contains("/battle") ? "battle" : "music";
			int len = ogg.seconds() > 0 ? Math.round(ogg.seconds() * 20) : 0;
			// Battle-music intro stingers and short jingles aren't songs: they're played before a loop or as a cue.
			if (isIntro(path) || (len > 0 && len < MIN_SONG_TICKS)) continue;
			out.add(new Track("file:" + file, file, name, ogg.artist == null ? "" : ogg.artist.trim(), group, packName(e.getValue()), len));
		}
		out.sort(Comparator.comparing(Track::group).thenComparing(Track::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(out);
	}

	/** {@code music/game/nether/crimson_forest} → "Nether · Crimson Forest"; {@code battle/wild_battle_kanto} → "Wild Battle Kanto". */
	static String pretty(String path) {
		String[] parts = path.split("/");
		String last = words(parts[parts.length - 1]);
		String parent = parts.length >= 2 ? parts[parts.length - 2] : "";
		boolean plain = Set.of("music", "game", "battle", "menu", "").contains(parent);
		return plain ? last : GAMES.getOrDefault(parent, words(parent)) + " · " + last;
	}

	private static String words(String s) {
		StringBuilder b = new StringBuilder();
		for (String w : s.replace('-', '_').split("_")) {
			if (w.isEmpty()) continue;
			if (b.length() > 0) b.append(' ');
			b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return b.toString();
	}

	/** Where a file comes from, for the panel: a resource pack's name, a mod's name, or "Minecraft". */
	private static String packName(Resource r) {
		String id = r.sourcePackId();
		if (id == null) return "";
		if (id.equals("vanilla")) return "Minecraft";
		if (id.startsWith("file/")) {
			String n = id.substring(5);
			return n.endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
		}
		return FabricLoader.getInstance().getModContainer(id).map(m -> m.getMetadata().getName()).orElse(id);
	}

	/** Reads an Ogg Vorbis stream once: sample rate and tags from the first pages, length from the last granule. */
	static final class Ogg {
		String title, artist;
		long rate, granule = -1, tlenMs = -1;

		float seconds() {
			if (rate > 0 && granule > 0) return granule / (float) rate;
			return tlenMs > 0 ? tlenMs / 1000f : 0;
		}

		void read(InputStream in) throws java.io.IOException {
			byte[] head = new byte[65536];
			int n = in.readNBytes(head, 0, head.length);
			parseHeaders(head, n);
			// keep the last 64 KB to find the final page's granule position
			byte[] tail = new byte[65536];
			System.arraycopy(head, 0, tail, 0, n);
			int len = n;
			byte[] buf = new byte[65536];
			int r;
			while ((r = in.read(buf)) > 0) {
				if (len + r <= tail.length) { System.arraycopy(buf, 0, tail, len, r); len += r; }
				else {
					int keep = Math.max(0, tail.length - r);
					System.arraycopy(tail, len - keep, tail, 0, keep);
					System.arraycopy(buf, Math.max(0, r - tail.length), tail, keep, Math.min(r, tail.length));
					len = keep + Math.min(r, tail.length);
				}
			}
			for (int i = len - 27; i >= 0; i--) {
				if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
					long g = le(tail, i + 6, 8);
					if (g > 0) { granule = g; break; }
				}
			}
		}

		private void parseHeaders(byte[] b, int n) {
			int id = find(b, n, (byte) 1);
			if (id >= 0 && id + 16 <= n) rate = le(b, id + 7 + 5, 4); // after "\1vorbis": version(4) channels(1) rate(4)
			int c = find(b, n, (byte) 3);
			if (c < 0) return;
			int p = c + 7;
			if (p + 4 > n) return;
			long vendor = le(b, p, 4);
			p += 4 + (int) vendor;
			if (vendor < 0 || p + 4 > n) return;
			long count = le(b, p, 4);
			p += 4;
			for (long i = 0; i < count && i < 200 && p + 4 <= n; i++) {
				int l = (int) le(b, p, 4);
				p += 4;
				if (l < 0 || p + l > n) return;
				String kv = new String(b, p, l, StandardCharsets.UTF_8);
				p += l;
				int eq = kv.indexOf('=');
				if (eq <= 0) continue;
				String k = kv.substring(0, eq).toLowerCase(Locale.ROOT), v = kv.substring(eq + 1);
				switch (k) {
					case "title" -> title = v;
					case "artist" -> artist = v;
					case "tlen" -> { try { tlenMs = Long.parseLong(v.trim()); } catch (NumberFormatException ignored) {} }
					default -> {}
				}
			}
		}

		/** Index of a Vorbis header packet of this type ({@code type} then "vorbis"), or -1. */
		private static int find(byte[] b, int n, byte type) {
			for (int i = 0; i + 7 <= n; i++)
				if (b[i] == type && b[i + 1] == 'v' && b[i + 2] == 'o' && b[i + 3] == 'r' && b[i + 4] == 'b' && b[i + 5] == 'i' && b[i + 6] == 's') return i;
			return -1;
		}

		private static long le(byte[] b, int at, int bytes) {
			long v = 0;
			for (int i = bytes - 1; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xFF);
			return v;
		}
	}

	/** Plays exactly one file, straight to the player, on the Jukebox/Note Blocks volume. */
	static final class FileSound extends AbstractSoundInstance {
		private final ResourceLocation file;

		FileSound(ResourceLocation file) {
			super(ResourceLocation.fromNamespaceAndPath("cvds", "jukebox"), SoundSource.RECORDS, RandomSource.create());
			this.file = file;
			this.volume = 1f;
			this.pitch = 1f;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}

		@Override
		public WeighedSoundEvents resolve(SoundManager manager) {
			this.sound = new Sound(file, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, true, false, 16);
			WeighedSoundEvents events = new WeighedSoundEvents(this.location, null);
			events.addSound(this.sound);
			return events;
		}
	}
}
