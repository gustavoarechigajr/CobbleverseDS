package dev.cvds.inv;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlannerTest {
	static final Map<String, Integer> MAX = Map.of("sword", 1, "pearl", 16, "egg", 16);
	static List<Integer> range(int a, int b) { return IntStream.range(a, b).boxed().toList(); }

	@Test void moveAllIntoEmpty() {
		SimInv inv = new SimInv(4, MAX).set(0, "stone", 40);
		Planner.move(inv, 0, 2, Planner.Amount.ALL);
		assertEquals("", inv.key(0));
		assertEquals(40, inv.count(2));
		assertFalse(inv.carrying());
	}

	@Test void moveAllOntoPartialReturnsRemainder() {
		SimInv inv = new SimInv(4, MAX).set(0, "stone", 40).set(1, "stone", 50);
		Planner.move(inv, 0, 1, Planner.Amount.ALL);
		assertEquals(64, inv.count(1));
		assertEquals(26, inv.count(0));
		assertFalse(inv.carrying());
	}

	@Test void moveOntoDifferentItemSwaps() {
		SimInv inv = new SimInv(4, MAX).set(0, "stone", 10).set(1, "dirt", 5);
		Planner.move(inv, 0, 1, Planner.Amount.ALL);
		assertEquals("dirt", inv.key(0));
		assertEquals(5, inv.count(0));
		assertEquals("stone", inv.key(1));
		assertEquals(10, inv.count(1));
		assertFalse(inv.carrying());
	}

	@Test void moveHalfAndOne() {
		SimInv inv = new SimInv(4, MAX).set(0, "stone", 9);
		Planner.move(inv, 0, 1, Planner.Amount.HALF);
		assertEquals(5, inv.count(1));
		assertEquals(4, inv.count(0));
		Planner.move(inv, 0, 2, Planner.Amount.ONE);
		assertEquals(1, inv.count(2));
		assertEquals(3, inv.count(0));
		assertFalse(inv.carrying());
	}

	@Test void moveHalfOntoDifferentItemLeavesBothIntact() {
		SimInv inv = new SimInv(4, MAX).set(0, "stone", 9).set(1, "dirt", 3);
		Planner.move(inv, 0, 1, Planner.Amount.HALF);
		assertFalse(inv.carrying());
		assertEquals(9, inv.total("stone"));
		assertEquals(3, inv.total("dirt"));
	}

	@Test void moveFromEmptyDoesNothing() {
		SimInv inv = new SimInv(4, MAX).set(1, "stone", 3);
		Planner.move(inv, 0, 1, Planner.Amount.ALL);
		assertEquals(0, inv.clicks);
	}

	@Test void mergeCombinesPartials() {
		SimInv inv = new SimInv(6, MAX).set(0, "stone", 10).set(2, "stone", 60).set(4, "stone", 30).set(5, "pearl", 3);
		Planner.merge(inv, range(0, 6));
		assertEquals(100, inv.total("stone"));
		long stacks = IntStream.range(0, 6).filter(i -> inv.key(i).equals("stone")).count();
		assertEquals(2, stacks);
		assertFalse(inv.carrying());
	}

	@Test void sortOrdersAndPacks() {
		SimInv inv = new SimInv(9, MAX).set(1, "zinc", 5).set(3, "apple", 2).set(5, "zinc", 7).set(8, "apple", 64);
		assertTrue(Planner.sort(inv, range(0, 9), Comparator.naturalOrder()));
		assertEquals(List.of("apple", "apple", "zinc", "", "", "", "", "", ""), IntStream.range(0, 9).mapToObj(inv::key).toList());
		assertEquals(64, inv.count(0));
		assertEquals(2, inv.count(1));
		assertEquals(12, inv.count(2));
	}

	@Test void sortOnlyTouchesGroup() {
		SimInv inv = new SimInv(6, MAX).set(0, "b", 1).set(1, "a", 1).set(4, "keep", 3);
		assertTrue(Planner.sort(inv, List.of(0, 1, 2), Comparator.naturalOrder()));
		assertEquals("a", inv.key(0));
		assertEquals("keep", inv.key(4));
		assertEquals(3, inv.count(4));
	}

	@Test void sortRefusesOversizedStacks() {
		SimInv inv = new SimInv(3, MAX).set(0, "stone", 200);
		assertFalse(Planner.sort(inv, range(0, 3), Comparator.naturalOrder()));
		assertEquals(0, inv.clicks);
	}

	/** Random inventories: sorting must keep every item, end packed + ordered, and never strand the cursor. */
	@Test void sortFuzz() {
		String[] kinds = {"stone", "dirt", "sword", "pearl", "egg", "log", "apple"};
		Random rnd = new Random(1234);
		for (int round = 0; round < 3000; round++) {
			int size = 1 + rnd.nextInt(54);
			SimInv inv = new SimInv(size, MAX);
			for (int i = 0; i < size; i++) {
				if (rnd.nextInt(3) == 0) continue;
				String k = kinds[rnd.nextInt(kinds.length)];
				inv.set(i, k, 1 + rnd.nextInt(MAX.getOrDefault(k, 64)));
			}
			Map<String, Integer> before = new HashMap<>();
			for (String k : kinds) before.put(k, inv.total(k));
			assertTrue(Planner.sort(inv, range(0, size), Comparator.naturalOrder()), "round " + round);
			assertFalse(inv.carrying(), "cursor left full, round " + round);
			for (String k : kinds) assertEquals(before.get(k), inv.total(k), "lost " + k + " in round " + round);
			boolean seenEmpty = false;
			String prev = "";
			for (int i = 0; i < size; i++) {
				if (inv.empty(i)) { seenEmpty = true; continue; }
				assertFalse(seenEmpty, "gap before slot " + i + " in round " + round);
				assertTrue(prev.compareTo(inv.key(i)) <= 0, "order broken at " + i + " in round " + round);
				prev = inv.key(i);
			}
			for (String k : kinds) {
				long partials = IntStream.range(0, size).filter(i -> inv.key(i).equals(k) && inv.count(i) < MAX.getOrDefault(k, 64)).count();
				assertTrue(partials <= 1, "unmerged " + k + " in round " + round);
			}
		}
	}

	@Test void moveFuzzConservesItems() {
		String[] kinds = {"stone", "sword", "pearl"};
		Random rnd = new Random(99);
		for (int round = 0; round < 3000; round++) {
			SimInv inv = new SimInv(6, MAX);
			for (int i = 0; i < 6; i++) if (rnd.nextBoolean()) { String k = kinds[rnd.nextInt(3)]; inv.set(i, k, 1 + rnd.nextInt(MAX.getOrDefault(k, 64))); }
			Map<String, Integer> before = new HashMap<>();
			for (String k : kinds) before.put(k, inv.total(k));
			Planner.move(inv, rnd.nextInt(6), rnd.nextInt(6), Planner.Amount.values()[rnd.nextInt(3)]);
			assertFalse(inv.carrying(), "round " + round);
			for (String k : kinds) assertEquals(before.get(k), inv.total(k), "round " + round);
		}
	}
}
