package dev.cvds.inv;

import java.util.*;

/** In-memory model of vanilla PICKUP clicks on plain slots, used by tests and dry runs. */
public final class SimInv implements SlotInv {
	private final String[] keys;
	private final int[] counts;
	private final Map<String, Integer> maxStack;
	private String carriedKey = "";
	private int carried = 0;
	public int clicks = 0;

	public SimInv(int size, Map<String, Integer> maxStack) {
		this.keys = new String[size];
		this.counts = new int[size];
		Arrays.fill(keys, "");
		this.maxStack = maxStack;
	}

	public SimInv set(int slot, String key, int count) { keys[slot] = count > 0 ? key : ""; counts[slot] = Math.max(count, 0); return this; }
	int stackMax(String key) { return maxStack.getOrDefault(key, 64); }

	@Override public String key(int s) { return keys[s]; }
	@Override public int count(int s) { return counts[s]; }
	@Override public int max(int s) { return keys[s].isEmpty() ? 64 : stackMax(keys[s]); }
	@Override public String carriedKey() { return carriedKey; }
	@Override public int carriedCount() { return carried; }

	@Override public void pickup(int s, int button) {
		clicks++;
		if (carried == 0) {
			if (counts[s] == 0) return;
			int take = button == 0 ? counts[s] : (counts[s] + 1) / 2;
			carriedKey = keys[s];
			carried = take;
			counts[s] -= take;
			if (counts[s] == 0) keys[s] = "";
			return;
		}
		if (counts[s] == 0) {
			int put = button == 0 ? Math.min(carried, stackMax(carriedKey)) : 1;
			keys[s] = carriedKey;
			counts[s] = put;
			carried -= put;
		} else if (keys[s].equals(carriedKey)) {
			int room = stackMax(carriedKey) - counts[s];
			int put = Math.min(room, button == 0 ? carried : 1);
			if (put > 0) { counts[s] += put; carried -= put; }
		} else if (carried <= stackMax(carriedKey)) {
			String k = keys[s]; int c = counts[s];
			keys[s] = carriedKey; counts[s] = carried;
			carriedKey = k; carried = c;
		}
		if (carried == 0) carriedKey = "";
	}

	public int total(String key) {
		int t = carriedKey.equals(key) ? carried : 0;
		for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) t += counts[i];
		return t;
	}
}
