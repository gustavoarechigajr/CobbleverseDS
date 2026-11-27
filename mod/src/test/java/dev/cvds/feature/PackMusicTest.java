package dev.cvds.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PackMusicTest {
	private static void le(ByteArrayOutputStream o, long v, int bytes) {
		for (int i = 0; i < bytes; i++) o.write((int) (v >> (8 * i)) & 0xFF);
	}

	/** A minimal Ogg Vorbis byte stream: identification + comment headers, padding, and a last page with a granule. */
	private static byte[] ogg(int rate, long lastGranule, int padding, String... tags) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII)); le(o, 0, 2); le(o, 0, 8); // first page, granule 0
		o.write(1); o.writeBytes("vorbis".getBytes(StandardCharsets.US_ASCII));
		le(o, 0, 4); o.write(2); le(o, rate, 4); le(o, 0, 12); o.write(0); o.write(1);
		o.write(3); o.writeBytes("vorbis".getBytes(StandardCharsets.US_ASCII));
		byte[] vendor = "Lavf".getBytes(StandardCharsets.UTF_8);
		le(o, vendor.length, 4); o.writeBytes(vendor);
		le(o, tags.length, 4);
		for (String t : tags) { byte[] b = t.getBytes(StandardCharsets.UTF_8); le(o, b.length, 4); o.writeBytes(b); }
		o.writeBytes(new byte[padding]); // audio data, long enough to push the last page past the first 64 KB
		o.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII)); le(o, 4, 2); le(o, lastGranule, 8);
		o.writeBytes(new byte[40]);
		return o.toByteArray();
	}

	@Test
	void readsTagsRateAndLengthFromTheLastPage() throws Exception {
		PackMusic.Ogg g = new PackMusic.Ogg();
		g.read(new ByteArrayInputStream(ogg(48000, 48000L * 136, 300_000, "title=Battle! N", "ARTIST=Game Freak", "genre=Game")));
		assertEquals("Battle! N", g.title);
		assertEquals("Game Freak", g.artist);
		assertEquals(48000, g.rate);
		assertEquals(136f, g.seconds(), 0.01f);
	}

	@Test
	void noTagsStillGivesTheLength() throws Exception {
		PackMusic.Ogg g = new PackMusic.Ogg();
		g.read(new ByteArrayInputStream(ogg(44100, 44100L * 71, 1000)));
		assertNull(g.title);
		assertEquals(71f, g.seconds(), 0.01f);
	}

	@Test
	void tidyNamesFromFilePaths() {
		assertEquals("Wild Battle Kanto", PackMusic.pretty("battle/wild_battle_kanto"));
		assertEquals("Ancestry", PackMusic.pretty("music/game/ancestry"));
		assertEquals("Nether · Concrete Halls", PackMusic.pretty("music/game/nether/concrete_halls"));
		assertEquals("Nightmare · Tower", PackMusic.pretty("music/nightmare/tower"));
		assertEquals("Black & White · Wild Rare", PackMusic.pretty("battle/bw/wild_rare"));
		assertEquals("HeartGold & SoulSilver · Trainer Johto", PackMusic.pretty("battle/hgss/trainer_johto"));
	}

	@Test
	void introStingersAreNotSongs() {
		org.junit.jupiter.api.Assertions.assertTrue(PackMusic.isIntro("battle/bw/intro/trainer-intro"));
		org.junit.jupiter.api.Assertions.assertTrue(PackMusic.isIntro("battle/xd/intro/battle_sim-intro"));
		org.junit.jupiter.api.Assertions.assertFalse(PackMusic.isIntro("battle/bw/wild"));
	}
}
