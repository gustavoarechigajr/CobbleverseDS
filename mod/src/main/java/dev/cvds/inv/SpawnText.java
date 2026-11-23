package dev.cvds.inv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Cobblemon's /checkspawn chat output ("Pikachu 12.5%, Zubat 3%, ..."). Pure, unit-tested. */
public final class SpawnText {
	private SpawnText() {}
	// Names can contain spaces, dots, apostrophes, colons, hyphens and gender symbols.
	static final Pattern ENTRY = Pattern.compile("(\\p{L}[\\p{L}\\p{N} .'\u2019:\\-\u2640\u2642]*?)\\s+(\\d+(?:[.,]\\d+)?)%");

	/** [name, percent] pairs in output order. */
	public static List<String[]> parse(List<String> lines) {
		List<String[]> out = new ArrayList<>();
		for (String line : lines) {
			// Each entry follows the header or a comma, so match entries one segment at a time.
			for (String seg : line.replaceFirst("^\\s*Spawns\\s*:", ",").split(",(?=\\s*\\p{L})")) {
			Matcher m = ENTRY.matcher(seg.trim());
			while (m.find()) {
				String name = m.group(1).trim();
				if (name.equalsIgnoreCase("Spawns")) continue;
				out.add(new String[]{name, m.group(2).replace(',', '.')});
			}
			}
		}
		return out;
	}
}
