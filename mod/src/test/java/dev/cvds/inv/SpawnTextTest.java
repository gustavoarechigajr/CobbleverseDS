package dev.cvds.inv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpawnTextTest {
	@Test void parsesTypicalOutput() {
		var r = SpawnText.parse(List.of("Spawns:", "Pikachu 12.5%, Zubat 3%, Mr. Mime 0.25%", "Farfetch\u2019d 1,5%, Nidoran\u2640 7%, Ho-Oh 0.01%, Type: Null 2%"));
		assertEquals(7, r.size());
		assertArrayEquals(new String[]{"Pikachu", "12.5"}, r.get(0));
		assertArrayEquals(new String[]{"Mr. Mime", "0.25"}, r.get(2));
		assertArrayEquals(new String[]{"Farfetch\u2019d", "1.5"}, r.get(3));
		assertArrayEquals(new String[]{"Nidoran\u2640", "7"}, r.get(4));
		assertArrayEquals(new String[]{"Ho-Oh", "0.01"}, r.get(5));
		assertArrayEquals(new String[]{"Type: Null", "2"}, r.get(6));
	}

	@Test void headerOnSameLine() {
		var r = SpawnText.parse(List.of("Spawns: Pikachu 12.5%, Mr. Mime 0.25%"));
		assertEquals(2, r.size());
		assertEquals("Pikachu", r.get(0)[0]);
		assertEquals("Mr. Mime", r.get(1)[0]);
	}

	@Test void cobblemonColonFormat() {
		// Real /checkspawn output on the Thor: "Name: percent%"
		var r = SpawnText.parse(List.of("Spawns:", "Sinistea: 73.48%, Varoom: 20.13%, Type: Null: 6.39%"));
		assertEquals(3, r.size());
		assertArrayEquals(new String[]{"Sinistea", "73.48"}, r.get(0));
		assertArrayEquals(new String[]{"Varoom", "20.13"}, r.get(1));
		assertArrayEquals(new String[]{"Type: Null", "6.39"}, r.get(2));
	}

	@Test void ignoresNonEntries() {
		assertTrue(SpawnText.parse(List.of("Nothing can spawn here right now.", "Invalid Spawn Bucket")).isEmpty());
	}
}
