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

	/** Plain item ids ("minecraft:chest") can be drawn without having been seen: the UI's own icons. */
	static boolean sampleFromId(String key) {
		if (key.contains("#")) return false;
		var id = net.minecraft.resources.ResourceLocation.tryParse(key);
		if (id == null) return false;
		var item = BuiltInRegistries.ITEM.getOptional(id);
		if (item.isEmpty() || item.get() == net.minecraft.world.item.Items.AIR) return false;
		SAMPLES.putIfAbsent(key, new ItemStack(item.get()));
		return true;
	}

	public static String key(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "";
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		var patch = stack.getComponentsPatch();
		// Durability is left out (the UI shows it as a bar): every point would be a new key and icon.
		patch = patch.forget(t -> t == net.minecraft.core.component.DataComponents.DAMAGE);
		String key = patch.isEmpty() ? id : id + "#" + Integer.toHexString(patch.hashCode());
		if (SAMPLES.size() > 20_000) SAMPLES.clear(); // bound memory; samples are re-added as items are seen
		SAMPLES.computeIfAbsent(key, k -> stack.copyWithCount(1));
		return key;
	}
}
