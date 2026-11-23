package dev.cvds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** config/cvds.json. Debug endpoints are only on with -Dcvds.debug=true or "debug": true. */
public final class Config {
	public int port = 25599;
	public boolean debug = false;

	static Config load() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("cvds.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Config c = new Config();
		try {
			if (Files.exists(p)) c = gson.fromJson(Files.readString(p), Config.class);
			if (c == null) c = new Config(); // empty file
			else Files.writeString(p, gson.toJson(c));
		} catch (Exception e) {
			CvdsClient.LOG.warn("cvds: could not read {}, using defaults", p, e);
			c = new Config();
		}
		if (Boolean.getBoolean("cvds.debug")) c.debug = true;
		return c;
	}
}
