package dev.cvds.feature;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Stable per-session identity for "this item with these components", shared by state and icons. */
public final class ItemKeys {
	private ItemKeys() {}
	/** One sample stack per key so the icon renderer can draw it later. */
	static final Map<String, ItemStack> SAMPLES = new ConcurrentHashMap<>();

	public static String key(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "";
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		var patch = stack.getComponentsPatch();
		String key = patch.isEmpty() ? id : id + "#" + Integer.toHexString(patch.hashCode());
		SAMPLES.computeIfAbsent(key, k -> stack.copyWithCount(1));
		return key;
	}
}
