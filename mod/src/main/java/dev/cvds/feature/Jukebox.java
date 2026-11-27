package dev.cvds.feature;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cvds.CvdsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;

/**
 * The panel's jukebox: plays the game's music-disc songs (any jukebox song the pack registers) and the pack's
 * own background and battle music ({@link PackMusic}) as a queue,
 * straight to the player like background music (no jukebox block, nothing changes in the world). Uses the
 * "Jukebox/Note Blocks" volume slider. The game's own background music waits while it plays.
 * Everything runs on the client thread (tick, and the HTTP routes go through {@code mc.submit}).
 */
public final class Jukebox {
	private Jukebox() {}

	static final int MAX_QUEUE = 200;
	/** A track that never became audible within this many ticks didn't play (e.g. that volume is at 0). */
	static final int START_GRACE_TICKS = 40;

	private static final List<String> queue = new ArrayList<>();
	private static int pos = 0;
	private static boolean playing = false;
	private static String repeat = "off"; // off | all | one
	private static SoundInstance inst;
	private static String nowId;
	private static int playedTicks, lengthTicks, failStreak;
	private static boolean everActive;
	private static String lastError = "";
	private static boolean loaded = false;

	/** Read by MusicManagerMixin: the game's background music stays quiet while the jukebox plays. */
	public static volatile boolean suppressMusic = false;

	// ---------- song list ----------

	/** kind: "disc", "music" (background) or "battle". file: set for pack music (played as that one file). */
	private record Song(String id, String name, int lenTicks, String disc, String kind, String artist, String src, ResourceLocation file) {}

	private static Object songsFor; // the registry the disc list was built from (one per world join)
	private static List<Song> discSongs = List.of();
	private static List<PackMusic.Track> musicFor; // the pack-music list the combined list was built from
	private static List<Song> songs = List.of();

	private static Registry<JukeboxSong> registry(Minecraft mc) {
		return mc.level == null ? null : mc.level.registryAccess().registryOrThrow(Registries.JUKEBOX_SONG);
	}

	static List<Song> songs(Minecraft mc) {
		Registry<JukeboxSong> reg = registry(mc);
		if (reg == null) return List.of();
		List<PackMusic.Track> music = PackMusic.tracks(mc);
		if (reg != songsFor) { discSongs = discs(reg); songsFor = reg; musicFor = null; }
		if (music != musicFor) {
			List<Song> all = new ArrayList<>(discSongs);
			for (PackMusic.Track tr : music)
				all.add(new Song(tr.id(), tr.name(), tr.lenTicks(), null, tr.group(), tr.artist(), tr.src(), tr.file()));
			songs = List.copyOf(all);
			musicFor = music;
		}
		return songs;
	}

	private static List<Song> discs(Registry<JukeboxSong> reg) {
		Map<ResourceLocation, String> discs = new HashMap<>();
		for (var item : BuiltInRegistries.ITEM) {
			JukeboxPlayable jp;
			try { jp = item.components().get(DataComponents.JUKEBOX_PLAYABLE); } catch (Throwable t) { continue; }
			if (jp == null) continue;
			jp.song().unwrap(reg).map(reg::getKey).ifPresent(id -> discs.putIfAbsent(id, BuiltInRegistries.ITEM.getKey(item).toString()));
		}
		List<Song> list = new ArrayList<>();
		for (var e : reg.entrySet()) {
			ResourceLocation id = e.getKey().location();
			JukeboxSong s = e.getValue();
			String name;
			try { name = s.description().getString(); } catch (Throwable t) { name = id.getPath(); }
			list.add(new Song(id.toString(), name, s.lengthInTicks(), discs.get(id), "disc", "", "", null));
		}
		// Vanilla first (C418, Lena Raine, ...), then the pack's own, each by name.
		list.sort(Comparator.comparing((Song s) -> !s.id().startsWith("minecraft:")).thenComparing(Song::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	private static Song song(Minecraft mc, String id) {
		for (Song s : songs(mc)) if (s.id().equals(id)) return s;
		return null;
	}

	/** Song ids of the discs in the player's inventory (main, armor and offhand slots). */
	private static Set<String> owned(Minecraft mc) {
		Set<String> out = new HashSet<>();
		Registry<JukeboxSong> reg = registry(mc);
		if (reg == null || mc.player == null) return out;
		var inv = mc.player.getInventory();
		for (var list : List.of(inv.items, inv.armor, inv.offhand))
			for (ItemStack st : list) {
				JukeboxPlayable jp = st.isEmpty() ? null : st.get(DataComponents.JUKEBOX_PLAYABLE);
				if (jp != null) jp.song().unwrap(reg).map(reg::getKey).ifPresent(id -> out.add(id.toString()));
			}
		return out;
	}

	// ---------- playback ----------

	public static void tick(Minecraft mc) {
		if (!loaded) load();
		if (!playing) { suppressMusic = false; return; }
		if (mc.level == null || mc.player == null) { stopSound(mc); playing = false; suppressMusic = false; return; } // left the world
		suppressMusic = true;
		if (inst == null) { start(mc); return; }
		if (mc.isPaused()) return; // the sound engine is paused too
		playedTicks++;
		boolean active = mc.getSoundManager().isActive(inst);
		if (active) { everActive = true; failStreak = 0; lastError = ""; }
		boolean ended = everActive ? !active : playedTicks > START_GRACE_TICKS;
		// Safety net: a song never runs (much) past its length.
		if (!ended && lengthTicks > 0 && playedTicks > lengthTicks + 200) ended = true;
		if (!ended) return;
		if (!everActive) {
			failStreak++;
			lastError = mc.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0 || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0
				? "Nothing to hear: the Jukebox/Note Blocks (or Master) volume is at 0 in Options → Music & Sounds."
				: "That song didn't start playing (is the game's sound working?).";
			if (failStreak >= Math.min(3, Math.max(1, queue.size()))) { stop(mc); return; }
		}
		stopSound(mc);
		advance(1, false);
		if (playing) start(mc);
	}

	/** Starts the song at {@code pos}, skipping ids this pack doesn't have. */
	private static void start(Minecraft mc) {
		stopSound(mc);
		for (int tries = 0; tries < queue.size() && playing; tries++) {
			if (pos < 0 || pos >= queue.size()) pos = 0;
			String id = queue.get(pos);
			Song s = song(mc, id);
			if (s == null && id.startsWith("file:") && !PackMusic.ready()) return; // pack music still being listed: try next tick
			if (s == null) { advance(1, true); continue; }
			if (s.file() != null) {
				mc.getMusicManager().stopPlaying(); // the background track, if one is on
				inst = new PackMusic.FileSound(s.file());
			} else {
				Registry<JukeboxSong> reg = registry(mc);
				JukeboxSong js = reg == null ? null : reg.get(ResourceLocation.tryParse(id));
				if (js == null) { advance(1, true); continue; }
				mc.getMusicManager().stopPlaying(); // the background track, if one is on
				ResourceLocation sound = js.soundEvent().value().getLocation();
				inst = new SimpleSoundInstance(sound, SoundSource.RECORDS, 1f, 1f, RandomSource.create(), false, 0,
					SoundInstance.Attenuation.NONE, 0, 0, 0, true);
			}
			nowId = id;
			playedTicks = 0;
			lengthTicks = s.lenTicks();
			everActive = false;
			mc.getSoundManager().play(inst);
			return;
		}
		// nothing in the queue can be played
		if (playing) { lastError = queue.isEmpty() ? "" : "None of the queued songs exist in this pack."; stop(mc); }
	}

	/** Moves {@code pos} by {@code step}; at the end of the queue it wraps (repeat all) or stops. */
	private static void advance(int step, boolean skipping) {
		if (queue.isEmpty()) { playing = false; pos = 0; return; }
		if (!skipping && "one".equals(repeat)) return; // same song again
		pos += step;
		if (pos >= queue.size()) {
			pos = 0;
			if (!"all".equals(repeat) && !skipping) playing = false;
		} else if (pos < 0) pos = "all".equals(repeat) ? queue.size() - 1 : 0;
	}

	private static void stopSound(Minecraft mc) {
		if (inst != null) mc.getSoundManager().stop(inst);
		inst = null;
		nowId = null;
		playedTicks = 0;
	}

	private static void stop(Minecraft mc) {
		stopSound(mc);
		playing = false;
		suppressMusic = false;
	}

	// ---------- HTTP (client thread) ----------

	static JsonObject json(Minecraft mc) {
		if (!loaded) load();
		JsonObject o = Api.ok();
		Set<String> have = owned(mc);
		JsonArray list = new JsonArray();
		JsonObject items = new JsonObject();
		for (Song s : songs(mc)) {
			JsonObject j = new JsonObject();
			j.addProperty("id", s.id());
			j.addProperty("name", s.name());
			j.addProperty("len", Math.round(s.lenTicks() / 20f));
			if (s.disc() != null) j.addProperty("k", s.disc()); // the disc's plain item id: its icon
			j.addProperty("kind", s.kind());
			if (!s.artist().isEmpty()) j.addProperty("artist", s.artist());
			if (!s.src().isEmpty()) j.addProperty("src", s.src());
			j.addProperty("have", have.contains(s.id()));
			list.add(j);
		}
		o.add("songs", list);
		o.addProperty("musicReady", PackMusic.ready());
		JsonArray q = new JsonArray();
		queue.forEach(q::add);
		o.add("queue", q);
		o.addProperty("pos", pos);
		o.addProperty("playing", playing);
		o.addProperty("repeat", repeat);
		if (nowId != null) {
			JsonObject now = new JsonObject();
			now.addProperty("id", nowId);
			now.addProperty("t", playedTicks / 20);
			now.addProperty("len", Math.round(lengthTicks / 20f));
			o.add("now", now);
		}
		o.addProperty("volume", Math.round(100 * mc.options.getSoundSourceVolume(SoundSource.RECORDS) * mc.options.getSoundSourceVolume(SoundSource.MASTER)));
		if (!lastError.isEmpty()) o.addProperty("error", lastError);
		return o;
	}

	static JsonObject act(Minecraft mc, JsonObject a) {
		if (!loaded) load();
		if (mc.level == null) return Api.error("not in a world");
		String op = a.has("op") ? a.get("op").getAsString() : "";
		switch (op) {
			case "add" -> { // {id} or {ids: [...]}: to the end of the queue
				List<String> ids = new ArrayList<>();
				if (a.has("ids")) a.getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
				else ids.add(a.get("id").getAsString());
				for (String id : ids) {
					if (song(mc, id) == null) return Api.error("no such song: " + id);
					if (queue.size() >= MAX_QUEUE) return Api.error("the queue is full (" + MAX_QUEUE + " songs)");
					queue.add(id);
				}
			}
			case "playNow" -> { // {id}: right after the current song, and start it
				String id = a.get("id").getAsString();
				if (song(mc, id) == null) return Api.error("no such song: " + id);
				if (queue.size() >= MAX_QUEUE) return Api.error("the queue is full (" + MAX_QUEUE + " songs)");
				int at = queue.isEmpty() ? 0 : Math.min(pos + (nowId != null ? 1 : 0), queue.size());
				queue.add(at, id);
				pos = at;
				playing = true;
				failStreak = 0;
				lastError = "";
				start(mc);
			}
			case "remove" -> { // {i}
				int i = a.get("i").getAsInt();
				if (i < 0 || i >= queue.size()) return Api.error("no song there");
				boolean current = i == pos && nowId != null;
				queue.remove(i);
				if (i < pos) pos--;
				if (current) {
					stopSound(mc);
					if (queue.isEmpty() || pos >= queue.size()) { pos = 0; if (!"all".equals(repeat) || queue.isEmpty()) playing = false; }
					if (playing) start(mc);
				}
				if (pos >= queue.size()) pos = 0;
				if (queue.isEmpty()) stop(mc);
			}
			case "move" -> { // {from, to}
				int from = a.get("from").getAsInt(), to = a.get("to").getAsInt();
				if (from < 0 || to < 0 || from >= queue.size() || to >= queue.size()) return Api.error("no song there");
				String id = queue.remove(from);
				queue.add(to, id);
				if (pos == from) pos = to;
				else if (from < pos && to >= pos) pos--;
				else if (from > pos && to <= pos) pos++;
			}
			case "clear" -> { stop(mc); queue.clear(); pos = 0; }
			case "play" -> { // {i}? : start the queue (at i)
				if (queue.isEmpty()) return Api.error("the queue is empty: add some songs first");
				if (a.has("i")) {
					int i = a.get("i").getAsInt();
					if (i < 0 || i >= queue.size()) return Api.error("no song there");
					pos = i;
				}
				playing = true;
				failStreak = 0;
				lastError = "";
				start(mc);
			}
			case "stop" -> stop(mc);
			case "next", "prev" -> {
				if (queue.isEmpty()) return Api.error("the queue is empty");
				boolean was = playing;
				// "Previous" a few seconds into a song starts it again, like any music player.
				boolean restart = op.equals("prev") && nowId != null && playedTicks > 60;
				stopSound(mc);
				if (!restart) advance(op.equals("next") ? 1 : -1, true);
				playing = was; // stopped: only the cursor moves
				if (playing) start(mc);
			}
			case "repeat" -> { // {mode: off|all|one}
				String m = a.get("mode").getAsString();
				if (!m.equals("off") && !m.equals("all") && !m.equals("one")) return Api.error("repeat is off, all or one");
				repeat = m;
			}
			case "shuffle" -> { // shuffles the queue once; the current song moves to the top and keeps playing
				String current = nowId != null && pos < queue.size() ? queue.remove(pos) : null;
				Collections.shuffle(queue);
				if (current != null) queue.add(0, current);
				pos = 0;
			}
			default -> { return Api.error("unknown jukebox op " + op); }
		}
		save();
		return json(mc);
	}

	// ---------- saved queue (config/cvds-jukebox.json) ----------

	private static final class Saved { List<String> queue = new ArrayList<>(); String repeat = "off"; }

	private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("cvds-jukebox.json"); }

	private static void load() {
		loaded = true;
		try {
			Path p = file();
			if (!Files.exists(p)) return;
			Saved s = new Gson().fromJson(Files.readString(p), Saved.class);
			if (s == null) return;
			queue.clear();
			if (s.queue != null) for (String id : s.queue) if (id != null && queue.size() < MAX_QUEUE) queue.add(id);
			if ("all".equals(s.repeat) || "one".equals(s.repeat)) repeat = s.repeat;
		} catch (Exception e) {
			CvdsClient.LOG.warn("cvds: could not read the jukebox queue", e);
		}
	}

	private static void save() {
		Saved s = new Saved();
		s.queue = new ArrayList<>(queue);
		s.repeat = repeat;
		try {
			Files.writeString(file(), new Gson().toJson(s));
		} catch (Exception e) {
			CvdsClient.LOG.warn("cvds: could not save the jukebox queue", e);
		}
	}
}
