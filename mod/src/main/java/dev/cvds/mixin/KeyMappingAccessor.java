package dev.cvds.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount") int cvds$getClickCount();
	@Accessor("clickCount") void cvds$setClickCount(int count);
}
