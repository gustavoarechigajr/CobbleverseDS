package dev.cvds.feature;

import dev.cvds.CvdsClient;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Reads and takes items from the Sophisticated Backpacks a player carries (inventory, offhand, armor
 * and any worn slot the mod registers). Reflection keeps it a soft dependency. Server side only:
 * backpack contents live on the (integrated) server.
 */
final class Backpacks {
	private Backpacks() {}
	static final boolean LOADED = FabricLoader.getInstance().isModLoaded("sophisticatedbackpacks");
	private static boolean warned = false;

	/** One slot inside a backpack. */
	record Slot(Object handler, int slot, ItemStack stack) {}

	interface Visitor { void visit(Slot s); }

	static void forEachSlot(Player player, Visitor v) {
		if (!LOADED) return;
		try {
			Class<?> pip = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider");
			Class<?> consumer = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider$BackpackInventorySlotConsumer");
			Class<?> wrapperClass = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
			Object provider = pip.getMethod("get").invoke(null);
			Method run = pip.getMethod("runOnBackpacks", Player.class, consumer);
			Method fromStack = wrapperClass.getMethod("fromStack", ItemStack.class);
			Object proxy = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[]{consumer}, (self, m, args) -> {
				switch (m.getName()) {
					case "hashCode": return System.identityHashCode(self);
					case "equals": return self == args[0];
					case "toString": return "cvds backpack visitor";
					default: break;
				}
				try { // one unreadable backpack shouldn't hide the others
					Object wrapper = fromStack.invoke(null, (ItemStack) args[0]);
					Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
					Method get = handler.getClass().getMethod("getStackInSlot", int.class);
					int n = slotCount(handler);
					for (int i = 0; i < n; i++) {
						ItemStack s = (ItemStack) get.invoke(handler, i);
						if (s != null && !s.isEmpty()) v.visit(new Slot(handler, i, s));
					}
				} catch (Throwable t) {
					if (!warned) { warned = true; CvdsClient.LOG.warn("cvds: could not read a backpack", t); }
				}
				return false; // keep visiting every backpack
			});
			run.invoke(provider, player, proxy);
		} catch (Throwable t) {
			if (!warned) { warned = true; CvdsClient.LOG.warn("cvds: could not read backpacks", t); }
		}
	}

	/** The Fabric port's handler has getSlotCount() (an int); its getSlots() is the list of slot objects. */
	private static int slotCount(Object handler) throws Exception {
		for (String name : new String[]{"getSlotCount", "getSlots"}) {
			Object r;
			try { r = handler.getClass().getMethod(name).invoke(handler); }
			catch (NoSuchMethodException ignored) { continue; }
			if (r instanceof Integer n) return n;
			if (r instanceof java.util.Collection<?> c) return c.size();
		}
		throw new NoSuchMethodException("slot count on " + handler.getClass());
	}

	static ItemStack extract(Slot s, int count) throws Exception {
		return (ItemStack) s.handler().getClass().getMethod("extractItem", int.class, int.class, boolean.class)
			.invoke(s.handler(), s.slot(), count, false);
	}

	/** Puts a stack back into the same backpack slot; returns what didn't fit. */
	static ItemStack insert(Slot s, ItemStack stack) throws Exception {
		return (ItemStack) s.handler().getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class)
			.invoke(s.handler(), s.slot(), stack, false);
	}
}
