package dev.cvds;

import dev.cvds.feature.*;
import dev.cvds.net.MiniHttpServer;
import java.net.InetAddress;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CvdsClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("cvds");
	public static Config config;
	private static MiniHttpServer server;

	@Override
	public void onInitializeClient() {
		config = Config.load();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Keys.tick();
			Snapshot.tick(mc);
			Icons.tick(mc);
			if (config.debug) Debug.tick(mc);
		});
		try {
			server = new MiniHttpServer(InetAddress.getLoopbackAddress(), config.port, new Api());
			LOG.info("cvds: companion UI at http://127.0.0.1:{}/ (debug={})", server.port(), config.debug);
		} catch (Exception e) {
			LOG.error("cvds: could not start the companion server on port {}", config.port, e);
		}
	}
}
