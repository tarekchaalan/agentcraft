package dev.agentcraft.client.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

/**
 * DevBridge: a localhost-only WebSocket server inside the client, used by tools/devbridge-cli.mjs,
 * tools/capture-screenshots.mjs and QA to drive the game (camera, screenshots, commands, screens...).
 *
 * <p>Protocol: one JSON object per text frame. Request {@code {"id":"1","type":"dev.state",...}};
 * response {@code {"id":"1","type":"dev.state","ok":true,...}} or
 * {@code {"id":"1","type":"dev.state","ok":false,"error":"..."}}. The {@code id} (a string or a
 * number) is echoed back exactly as sent, also on errors. Requests may run concurrently; match
 * responses by id. On connect the server sends {@code {"type":"dev.hello",...}}. Binary frames
 * holding UTF-8 JSON are treated like text frames. Replies include null-valued fields.
 *
 * <p>Other parts of the mod extend it with {@link #register}, {@link #registerScreen} and
 * {@link #addStateContributor}. Handlers are invoked on the websocket thread and must hop to
 * the client thread with {@link #onClient} for any game access.
 */
public final class DevBridge extends WebSocketServer {
	public static final int PROTOCOL = 1;
	/** Serializes nulls so documented fields (e.g. dev.state {@code screen}, {@code foreman}) are always present. */
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	/** Upper bound for a request's {@code timeoutMs}. */
	public static final long MAX_TIMEOUT_MS = 3_600_000L;

	@FunctionalInterface
	public interface Handler {
		/** Called on the websocket thread. Return a future of the response payload (fields merged into the reply). */
		CompletableFuture<JsonObject> handle(JsonObject request, Minecraft mc) throws Exception;
	}

	/** {@code defaultTimeoutMs} computes the server-side timeout from the request (used when it has no explicit timeoutMs). */
	private record Registered(Handler handler, ToLongFunction<JsonObject> defaultTimeoutMs, String help) {
	}

	private static final Map<String, Registered> HANDLERS = new ConcurrentHashMap<>();
	private static final Map<String, Function<Minecraft, Screen>> SCREENS = new ConcurrentHashMap<>();
	private static final List<BiConsumer<Minecraft, JsonObject>> STATE_CONTRIBUTORS = new CopyOnWriteArrayList<>();
	private static volatile DevBridge instance;
	private static volatile String status = "not started";

	private DevBridge(int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		setDaemon(true);
		setTcpNoDelay(true);
		setConnectionLostTimeout(0);
	}

	// ---------------------------------------------------------------- extension API

	/** Register (or replace) a command. {@code timeoutMs} is the default server-side timeout. */
	public static void register(String type, long timeoutMs, String help, Handler handler) {
		HANDLERS.put(type, new Registered(handler, req -> timeoutMs, help));
	}

	/**
	 * Register a command whose default timeout depends on the request (e.g. a wait whose length is
	 * a request field). Used when the request carries no explicit {@code timeoutMs}.
	 */
	public static void register(String type, ToLongFunction<JsonObject> timeoutMs, String help, Handler handler) {
		HANDLERS.put(type, new Registered(handler, timeoutMs, help));
	}

	/**
	 * Run a registered command from mod code (e.g. a {@code dev.record} timeline) as if a client had
	 * sent {@code request}. Called on any thread; the handler hops to the client thread itself (inline
	 * when already on it). No timeout is applied.
	 */
	public static CompletableFuture<JsonObject> invoke(String type, JsonObject request) {
		Registered reg = HANDLERS.get(type);
		if (reg == null) {
			return CompletableFuture.failedFuture(new DevException("unknown type '" + type + "' (try dev.help)"));
		}
		try {
			CompletableFuture<JsonObject> f = reg.handler().handle(request, Minecraft.getInstance());
			return f != null ? f : CompletableFuture.failedFuture(new IllegalStateException("handler returned no future"));
		} catch (Throwable t) {
			return CompletableFuture.failedFuture(t);
		}
	}

	/** Make a screen openable via {@code dev.screen {open:name}}. */
	public static void registerScreen(String name, Function<Minecraft, Screen> factory) {
		SCREENS.put(name, factory);
	}

	public static Map<String, Function<Minecraft, Screen>> screens() {
		return SCREENS;
	}

	/** Add fields to the dev.state response (called on the client thread). */
	public static void addStateContributor(BiConsumer<Minecraft, JsonObject> contributor) {
		STATE_CONTRIBUTORS.add(contributor);
	}

	static List<BiConsumer<Minecraft, JsonObject>> stateContributors() {
		return STATE_CONTRIBUTORS;
	}

	public static String status() {
		return status;
	}

	/** Run {@code work} on the client (render) thread and complete with its result. */
	public static <T> CompletableFuture<T> onClient(Minecraft mc, Supplier<T> work) {
		CompletableFuture<T> f = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				f.complete(work.get());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	// ---------------------------------------------------------------- lifecycle

	public static synchronized void startBridge() {
		if (instance != null) {
			return;
		}
		if (!ClientEnv.DEV_BRIDGE) {
			status = "disabled (AGENTCRAFT_DEV=0)";
			AgentCraft.LOGGER.info("DevBridge disabled");
			return;
		}
		DevCommands.registerBuiltins();
		int port = ClientEnv.DEV_PORT;
		DevBridge server = new DevBridge(port);
		instance = server;
		status = "starting on 127.0.0.1:" + port;
		server.start();
	}

	public static synchronized void stopBridge() {
		DevBridge server = instance;
		instance = null;
		if (server != null) {
			try {
				server.stop(500, "game stopping");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("DevBridge stop failed", t);
			}
			status = "stopped";
		}
	}

	// ---------------------------------------------------------------- websocket callbacks

	@Override
	public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
		// Browsers always send Origin; local tools (node ws, curl) don't. Refusing browser
		// origins stops a web page from driving the game through ws://127.0.0.1.
		if (request.hasFieldValue("Origin") && !ClientEnv.flag("AGENTCRAFT_DEV_ALLOW_ORIGIN", false)) {
			throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "DevBridge refuses browser origins");
		}
		return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
	}

	@Override
	public void onStart() {
		status = "listening on 127.0.0.1:" + getPort();
		AgentCraft.LOGGER.info("DevBridge {}", status);
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		JsonObject hello = new JsonObject();
		hello.addProperty("type", "dev.hello");
		hello.addProperty("protocol", PROTOCOL);
		hello.addProperty("mod", AgentCraft.MOD_ID);
		hello.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
		Minecraft mc = Minecraft.getInstance();
		hello.addProperty("inWorld", mc != null && mc.level != null && mc.player != null);
		conn.send(GSON.toJson(hello));
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		if (conn == null) {
			// Server-level failure (e.g. port already in use). Never take the game down for this.
			status = "failed: " + ex;
			AgentCraft.LOGGER.error("DevBridge server error (port {}): {}", ClientEnv.DEV_PORT, ex.toString());
			if (instance == this) {
				instance = null;
			}
		} else {
			AgentCraft.LOGGER.warn("DevBridge connection error: {}", ex.toString());
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		JsonElement id = null;
		String type = null;
		try {
			JsonElement parsed;
			try {
				parsed = JsonParser.parseString(message);
			} catch (com.google.gson.JsonParseException e) {
				throw new DevException("invalid JSON");
			}
			if (!parsed.isJsonObject()) {
				throw new DevException("request must be a JSON object");
			}
			JsonObject req = parsed.getAsJsonObject();
			// Echo the id back exactly as given (also a malformed one) so the caller can correlate the error.
			JsonElement rawId = req.get("id");
			if (rawId != null && !rawId.isJsonNull()) {
				id = rawId;
				if (!(rawId.isJsonPrimitive() && (rawId.getAsJsonPrimitive().isString() || rawId.getAsJsonPrimitive().isNumber()))) {
					throw new DevException("'id' must be a string or a number (got " + Fields.describe(rawId) + ")");
				}
			}
			JsonElement rawType = req.get("type");
			if (rawType == null || rawType.isJsonNull()) {
				throw new DevException("missing 'type'");
			}
			if (!(rawType.isJsonPrimitive() && rawType.getAsJsonPrimitive().isString())) {
				throw new DevException("'type' must be a string (got " + Fields.describe(rawType) + ")");
			}
			String t = rawType.getAsString();
			Registered reg = HANDLERS.get(t);
			if (reg == null) {
				throw new DevException("unknown type '" + t + "' (try dev.help)");
			}
			type = t;
			// The DevBridge runs commands with full permissions and switches game modes; none of that
			// belongs anywhere near a Hardcore world.
			Minecraft current = Minecraft.getInstance();
			if (!t.equals("dev.help") && current != null && current.level != null && current.level.getLevelData().isHardcore()) {
				throw new DevException("the DevBridge is disabled while a Hardcore world is loaded");
			}
			Fields meta = Fields.of(req);
			long timeout = meta.has("timeoutMs")
				? meta.integer("timeoutMs", 1, MAX_TIMEOUT_MS)
				: Math.max(1, Math.min(MAX_TIMEOUT_MS, reg.defaultTimeoutMs().applyAsLong(req)));
			Minecraft mc = Minecraft.getInstance();
			CompletableFuture<JsonObject> future = reg.handler().handle(req, mc);
			if (future == null) {
				throw new IllegalStateException("handler returned no future");
			}
			final JsonElement fid = id;
			final String ftype = type;
			future.orTimeout(timeout, TimeUnit.MILLISECONDS).whenComplete((result, err) -> {
				if (err != null) {
					reply(conn, error(fid, ftype, err));
				} else {
					JsonObject out = new JsonObject();
					if (fid != null) {
						out.add("id", fid);
					}
					out.addProperty("type", ftype);
					out.addProperty("ok", true);
					if (result != null) {
						for (Map.Entry<String, JsonElement> e : result.entrySet()) {
							out.add(e.getKey(), e.getValue());
						}
					}
					reply(conn, out);
				}
			});
		} catch (Throwable t) {
			reply(conn, error(id, type, t));
		}
	}

	/** Binary frames are accepted when they hold UTF-8 text (some clients send buffers); anything else gets an error reply. */
	@Override
	public void onMessage(WebSocket conn, ByteBuffer bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(bytes)
				.toString();
		} catch (CharacterCodingException e) {
			reply(conn, error(null, null, new DevException("binary frame is not UTF-8 text; send one JSON object per text frame")));
			return;
		}
		onMessage(conn, text);
	}

	private static void reply(WebSocket conn, JsonObject obj) {
		try {
			if (conn.isOpen()) {
				conn.send(GSON.toJson(obj));
			}
		} catch (Throwable t) {
			AgentCraft.LOGGER.warn("DevBridge reply failed: {}", t.toString());
		}
	}

	private static JsonObject error(JsonElement id, String type, Throwable t) {
		Throwable cause = t;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		JsonObject out = new JsonObject();
		if (id != null) {
			out.add("id", id);
		}
		if (type != null) {
			out.addProperty("type", type);
		}
		out.addProperty("ok", false);
		String msg;
		if (cause instanceof TimeoutException) {
			msg = cause.getMessage() != null ? cause.getMessage() : "timed out";
			// Tell "slow" apart from "the render thread is stuck" (dev.ping reports the same numbers).
			if (FrameScheduler.stalled()) {
				msg += "; the render thread has not finished a frame for " + FrameScheduler.msSinceLastFrame() + " ms (game hung?)."
					+ " dev.ping shows the frame age; dev.quit force-exits a hung game";
				out.addProperty("stalled", true);
			}
		} else if (cause instanceof DevException) {
			msg = cause.getMessage();
		} else {
			// Not an expected user error: keep the exception class and log the stack for debugging.
			msg = "internal error: " + cause;
			AgentCraft.LOGGER.warn("DevBridge {} failed", type, cause);
		}
		out.addProperty("error", msg == null ? cause.getClass().getSimpleName() : msg);
		return out;
	}

	/** The error text a reply would carry for {@code t} (unwraps CompletionException). */
	public static String describeError(Throwable t) {
		JsonObject o = error(null, null, t);
		return o.get("error").getAsString();
	}

	/** Help text for every registered command, sorted. */
	static JsonObject help() {
		JsonObject cmds = new JsonObject();
		new TreeMap<>(HANDLERS).forEach((k, v) -> cmds.addProperty(k, v.help()));
		JsonObject out = new JsonObject();
		out.add("commands", cmds);
		JsonObject screens = new JsonObject();
		new TreeMap<>(SCREENS).keySet().forEach(k -> screens.addProperty(k, true));
		out.add("screens", screens);
		return out;
	}

	/** A user-facing error (message is sent back verbatim, no stack trace logged). */
	public static final class DevException extends RuntimeException {
		public DevException(String message) {
			super(message);
		}
	}
}
