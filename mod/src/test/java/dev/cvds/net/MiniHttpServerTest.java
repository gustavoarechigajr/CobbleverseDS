package dev.cvds.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MiniHttpServerTest {
	static MiniHttpServer.Request parse(String raw) throws IOException {
		return MiniHttpServer.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
	}

	@Test void parsesGetWithQuery() throws IOException {
		var r = parse("GET /api/state?rev=12&wait=500 HTTP/1.1\r\nHost: x\r\n\r\n");
		assertEquals("GET", r.method());
		assertEquals("/api/state", r.path());
		assertEquals("12", r.query().get("rev"));
		assertEquals("x", r.headers().get("host"));
	}

	@Test void parsesPostBodyAndEncodedPath() throws IOException {
		var r = parse("POST /api/icon/minecraft%3Astone HTTP/1.1\r\nContent-Length: 7\r\n\r\n{\"a\":1}");
		assertEquals("/api/icon/minecraft:stone", r.path());
		assertEquals("{\"a\":1}", r.bodyText());
	}

	@Test void acceptsBareLf() throws IOException {
		var r = parse("GET / HTTP/1.0\nA: b\n\n");
		assertEquals("/", r.path());
		assertEquals("b", r.headers().get("a"));
	}

	@Test void rejectsHugeBodyAndHeaders() {
		assertThrows(IOException.class, () -> parse("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n"));
		assertThrows(IOException.class, () -> parse("POST / HTTP/1.1\r\nContent-Length: -4\r\n\r\n"));
		assertThrows(IOException.class, () -> parse("GET / HTTP/1.1\r\nX: " + "a".repeat(20000) + "\r\n\r\n"));
		assertThrows(IOException.class, () -> parse("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"));
	}

	@Test void emptyRequestIsNull() throws IOException {
		assertNull(parse(""));
	}

	@Test void badPercentEncodingDoesNotThrow() throws IOException {
		assertEquals("/a%zz", parse("GET /a%zz HTTP/1.1\r\n\r\n").path());
	}

	@Test void servesOverRealSocket() throws Exception {
		try (var srv = new MiniHttpServer(InetAddress.getLoopbackAddress(), 0, req ->
				req.path().equals("/boom") ? (MiniHttpServer.Response) (Object) thrower() : MiniHttpServer.Response.json("{\"path\":\"" + req.path() + "\",\"body\":" + req.body().length + "}"))) {
			HttpClient c = HttpClient.newHttpClient();
			var base = "http://127.0.0.1:" + srv.port();
			var r = c.send(HttpRequest.newBuilder(URI.create(base + "/x")).POST(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, r.statusCode());
			assertEquals("{\"path\":\"/x\",\"body\":5}", r.body());
			var boom = c.send(HttpRequest.newBuilder(URI.create(base + "/boom")).build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(500, boom.statusCode());
			// Many concurrent clients (the UI long-polls while also posting actions).
			var threads = new java.util.ArrayList<Thread>();
			var failures = new java.util.concurrent.atomic.AtomicInteger();
			for (int i = 0; i < 32; i++) {
				Thread t = new Thread(() -> {
					try {
						var rr = c.send(HttpRequest.newBuilder(URI.create(base + "/p")).build(), HttpResponse.BodyHandlers.ofString());
						if (rr.statusCode() != 200) failures.incrementAndGet();
					} catch (Exception e) { failures.incrementAndGet(); }
				});
				t.start();
				threads.add(t);
			}
			for (Thread t : threads) t.join();
			assertEquals(0, failures.get());
		}
	}

	static Object thrower() { throw new IllegalStateException("boom"); }

	@Test void bindsLoopbackOnly() throws Exception {
		try (var srv = new MiniHttpServer(InetAddress.getLoopbackAddress(), 0, req -> MiniHttpServer.Response.json("{}"))) {
			// The companion UI must not be reachable from the network.
			for (var nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				for (var addr : java.util.Collections.list(nif.getInetAddresses())) {
					if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
					try (Socket s = new Socket()) {
						assertThrows(IOException.class, () -> s.connect(new InetSocketAddress(addr, srv.port()), 300));
					}
				}
			}
		}
	}
}
