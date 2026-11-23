package dev.cvds.inv;

import java.util.*;

/**
 * Multi-click inventory operations expressed as vanilla PICKUP clicks. Every operation
 * leaves the cursor empty when it can; callers check {@link SlotInv#carrying()} afterwards.
 */
public final class Planner {
	private Planner() {}

	public enum Amount { ALL, HALF, ONE }

	/** Moves (or swaps) the stack in {@code from} onto {@code to}; any remainder goes back to {@code from}. */
	public static void move(SlotInv inv, int from, int to, Amount amount) {
		if (from == to || inv.empty(from) || inv.carrying()) return;
		switch (amount) {
			case ALL -> { inv.pickup(from, 0); inv.pickup(to, 0); }
			case HALF -> { inv.pickup(from, 1); inv.pickup(to, 0); }
			case ONE -> { inv.pickup(from, 0); inv.pickup(to, 1); }
		}
		if (inv.carrying()) inv.pickup(from, 0);
		if (inv.carrying()) inv.pickup(to, 0);
	}

	/**
	 * Merges partial stacks, then orders the group by {@code order} (then larger stacks first),
	 * packing everything to the front. Returns false (doing nothing) when the group holds stacks
	 * larger than one cursor-full, which pickup clicks cannot move intact.
	 */
	public static boolean sort(SlotInv inv, List<Integer> group, Comparator<String> order) {
		if (inv.carrying()) return false;
		for (int s : group) if (!inv.empty(s) && inv.count(s) > inv.max(s)) return false;
		merge(inv, group);
		if (inv.carrying()) return false;

		List<String> keys = new ArrayList<>();
		List<Integer> counts = new ArrayList<>();
		for (int s : group) if (!inv.empty(s)) { keys.add(inv.key(s)); counts.add(inv.count(s)); }
		Integer[] idx = new Integer[keys.size()];
		for (int i = 0; i < idx.length; i++) idx[i] = i;
		Arrays.sort(idx, (a, b) -> {
			int c = order.compare(keys.get(a), keys.get(b));
			if (c != 0) return c;
			c = keys.get(a).compareTo(keys.get(b));
			return c != 0 ? c : Integer.compare(counts.get(b), counts.get(a));
		});
		for (int t = 0; t < idx.length; t++) {
			String wantKey = keys.get(idx[t]);
			int wantCount = counts.get(idx[t]);
			int target = group.get(t);
			if (inv.key(target).equals(wantKey) && inv.count(target) == wantCount) continue;
			int src = -1;
			for (int j = t + 1; j < group.size(); j++) {
				int s = group.get(j);
				if (inv.key(s).equals(wantKey) && inv.count(s) == wantCount) { src = s; break; }
			}
			if (src < 0) return false; // state diverged (e.g. server rejected a click)
			inv.pickup(src, 0);
			inv.pickup(target, 0);
			if (inv.carrying()) inv.pickup(src, 0);
			if (inv.carrying()) return false;
		}
		return true;
	}

	/** Combines partial stacks of the same item within the group. */
	public static void merge(SlotInv inv, List<Integer> group) {
		Map<String, List<Integer>> partial = new LinkedHashMap<>();
		for (int s : group) {
			if (inv.empty(s) || inv.count(s) >= inv.max(s)) continue;
			partial.computeIfAbsent(inv.key(s), k -> new ArrayList<>()).add(s);
		}
		for (List<Integer> slots : partial.values()) {
			int lo = 0, hi = slots.size() - 1;
			while (lo < hi) {
				int dst = slots.get(lo), src = slots.get(hi);
				if (inv.count(dst) >= inv.max(dst)) { lo++; continue; }
				if (inv.empty(src)) { hi--; continue; }
				inv.pickup(src, 0);
				inv.pickup(dst, 0);
				if (inv.carrying()) inv.pickup(src, 0);
				if (inv.carrying()) return;
			}
		}
	}
}
