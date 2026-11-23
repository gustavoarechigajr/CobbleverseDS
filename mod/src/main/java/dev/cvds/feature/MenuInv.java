package dev.cvds.feature;

import dev.cvds.inv.SlotInv;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/** SlotInv over the player's open menu. Clicks go through the normal client path (predicted + sent). */
final class MenuInv implements SlotInv {
	final Minecraft mc;
	final AbstractContainerMenu menu;

	MenuInv(Minecraft mc) {
		this.mc = mc;
		this.menu = mc.player.containerMenu;
	}

	boolean valid(int slot) { return slot >= 0 && slot < menu.slots.size(); }
	ItemStack stack(int slot) { return menu.getSlot(slot).getItem(); }

	@Override public String key(int s) { return ItemKeys.key(stack(s)); }
	@Override public int count(int s) { return stack(s).getCount(); }
	@Override public int max(int s) {
		ItemStack st = stack(s);
		return st.isEmpty() ? 64 : menu.getSlot(s).getMaxStackSize(st);
	}
	@Override public String carriedKey() { return ItemKeys.key(menu.getCarried()); }
	@Override public int carriedCount() { return menu.getCarried().getCount(); }
	@Override public void pickup(int s, int button) { click(s, button, ClickType.PICKUP); }

	void click(int slot, int button, ClickType type) {
		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
	}
}
