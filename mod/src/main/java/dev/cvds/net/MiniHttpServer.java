package dev.cvds.net;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tiny HTTP/1.1 server (one request per connection). The game's Java runtime on Android
 * is not guaranteed to ship jdk.httpserver, so this only uses java.net.
 */
public final class MiniHttpServer implements Closeable {
	public record Request(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body) {
		public String bodyText() { return new String(body, StandardCharsets.UTF_8); }
	}

	public record Response(int status, String contentType, byte[] body, Map<String, String> headers) {
		public static Response text(int status, String contentType, String body) {
			return new Response(status, contentType, body.getBytes(StandardCharsets.UTF_8), Map.of());
		}
		public static Response json(String body) { return text(200, "application/json; charset=utf-8", body); }
		public static Response notFound() { return text(404, "text/plain", "not found"); }
	}

	public interface Handler { Response handle(Request req) throws Exception; }

	static final int MAX_HEADER_BYTES = 16 * 1024;
	static final int MAX_BODY_BYTES = 1024 * 1024;

	private final ServerSocket socket;
	private final ExecutorService pool;
	private final Handler handler;
	private volatile boolean running = true;

	public MiniHttpServer(InetAddress bind, int port, Handler handler) throws IOException {
		this.socket = new ServerSocket();
		this.socket.setReuseAddress(true);
		this.socket.bind(new InetSocketAddress(bind, port));
		this.handler = handler;
		this.pool = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "cvds-http");
			t.setDaemon(true);
			return t;
		});
		Thread accept = new Thread(this::acceptLoop, "cvds-http-accept");
		accept.setDaemon(true);
		accept.start();
	}

	public int port() { return socket.getLocalPort(); }

	private void acceptLoop() {
		while (running) {
			try {
				Socket s = socket.accept();
				pool.execute(() -> serve(s));
			} catch (IOException e) {
				if (!running) return;
			}
		}
	}

	private void serve(Socket s) {
		try (s) {
			s.setSoTimeout(15_000);
			InputStream in = new BufferedInputStream(s.getInputStream());
			OutputStream out = new BufferedOutputStream(s.getOutputStream());
			Response resp;
			try {
				Request req = parse(in);
				resp = req == null ? Response.text(400, "text/plain", "bad request") : handler.handle(req);
			} catch (HttpError e) {
				resp = Response.text(e.status, "text/plain", e.getMessage());
			} catch (Exception e) {
				resp = Response.text(500, "text/plain", String.valueOf(e));
			}
			write(out, resp);
		} catch (IOException ignored) {
		}
	}

	static final class HttpError extends IOException {
		final int status;
		HttpError(int status, String msg) { super(msg); this.status = status; }
	}

	static Request parse(InputStream in) throws IOException {
		String requestLine = readLine(in, new int[]{0});
		if (requestLine == null || requestLine.isEmpty()) return null;
		String[] parts = requestLine.split(" ");
		if (parts.length < 2) return null;
		int[] budget = {requestLine.length()};
		Map<String, String> headers = new HashMap<>();
		String line;
		while ((line = readLine(in, budget)) != null && !line.isEmpty()) {
			int c = line.indexOf(':');
			if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
		}
		int len = 0;
		if (headers.containsKey("content-length")) {
			try { len = Integer.parseInt(headers.get("content-length")); } catch (NumberFormatException e) { throw new HttpError(400, "bad content-length"); }
		}
		if (len < 0 || len > MAX_BODY_BYTES) throw new HttpError(413, "body too large");
		byte[] body = in.readNBytes(len);
		if (body.length != len) throw new HttpError(400, "short body");
		String target = parts[1];
		int q = target.indexOf('?');
		String path = urlDecode(q < 0 ? target : target.substring(0, q));
		Map<String, String> query = q < 0 ? Map.of() : parseQuery(target.substring(q + 1));
		return new Request(parts[0].toUpperCase(Locale.ROOT), path, query, headers, body);
	}

	static Map<String, String> parseQuery(String qs) {
		Map<String, String> m = new LinkedHashMap<>();
		for (String kv : qs.split("&")) {
			if (kv.isEmpty()) continue;
			int e = kv.indexOf('=');
			if (e < 0) m.put(urlDecode(kv), "");
			else m.put(urlDecode(kv.substring(0, e)), urlDecode(kv.substring(e + 1)));
		}
		return m;
	}

	static String urlDecode(String s) {
		try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return s; }
	}

	/** Reads one CRLF/LF-terminated line, enforcing a shared header byte budget. */
	static String readLine(InputStream in, int[] budget) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') break;
			if (++budget[0] > MAX_HEADER_BYTES) throw new HttpError(431, "headers too large");
			buf.write(b);
		}
		if (b == -1 && buf.size() == 0) return null;
		String s = buf.toString(StandardCharsets.UTF_8);
		return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
	}

	static void write(OutputStream out, Response r) throws IOException {
		StringBuilder h = new StringBuilder();
		h.append("HTTP/1.1 ").append(r.status()).append(' ').append(reason(r.status())).append("\r\n");
		h.append("Content-Type: ").append(r.contentType()).append("\r\n");
		h.append("Content-Length: ").append(r.body().length).append("\r\n");
		h.append("Cache-Control: no-store\r\n");
		h.append("Connection: close\r\n");
		for (var e : r.headers().entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
		h.append("\r\n");
		out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.write(r.body());
		out.flush();
	}

	static String reason(int status) {
		return switch (status) {
			case 200 -> "OK"; case 202 -> "Accepted"; case 400 -> "Bad Request"; case 403 -> "Forbidden";
			case 404 -> "Not Found"; case 409 -> "Conflict"; case 413 -> "Payload Too Large";
			case 431 -> "Request Header Fields Too Large"; case 503 -> "Service Unavailable";
			default -> status >= 500 ? "Server Error" : "Status";
		};
	}

	@Override public void close() throws IOException {
		running = false;
		socket.close();
		pool.shutdownNow();
	}
}
