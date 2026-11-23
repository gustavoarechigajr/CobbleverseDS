package dev.cvds.feature;

import net.minecraft.client.gui.screens.Screen;

/** Kept separate so Snapshot never loads Cobblemon classes when Cobblemon is absent. */
final class PcScreen {
	private PcScreen() {}
	static boolean isPc(Screen s) { return s instanceof com.cobblemon.mod.common.client.gui.pc.PCGUI; }
	static boolean inBattle() { return com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null; }
}
