package dev.cvds.inv;

/**
 * The slice of a container menu the planners need. The game implementation wraps an
 * AbstractContainerMenu (client prediction updates it immediately after each click);
 * tests use {@link SimInv}, which models vanilla's PICKUP semantics.
 */
public interface SlotInv {
	/** Stable identity of the item + components in a slot, or "" when empty. */
	String key(int slot);
	int count(int slot);
	/** Most items this slot can hold of its current item (min of item and slot limits). */
	int max(int slot);
	String carriedKey();
	int carriedCount();
	/** Vanilla PICKUP click: button 0 = left, 1 = right. */
	void pickup(int slot, int button);

	default boolean empty(int slot) { return key(slot).isEmpty() || count(slot) <= 0; }
	default boolean carrying() { return !carriedKey().isEmpty() && carriedCount() > 0; }
}
