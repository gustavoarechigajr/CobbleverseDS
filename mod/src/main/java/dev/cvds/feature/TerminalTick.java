package dev.cvds.feature;

import net.minecraft.client.Minecraft;

/** Public, Tom's-free entry point so CvdsClient never links against Terminal directly. */
public final class TerminalTick {
	private TerminalTick() {}
	public static void tick(Minecraft mc) { Terminal.tick(mc); }
}
