package dev.agentcraft.client.mp.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.DevBridge.DevException;
import dev.agentcraft.client.dev.DevCommands;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.HelloC2S;
import dev.agentcraft.mp.net.PublicStateC2S;
import dev.agentcraft.mp.net.StudioEventC2S;
import dev.agentcraft.mp.net.WorldIntentC2S;
import dev.agentcraft.mp.state.MpText;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/**
 * DevBridge hooks for multiplayer. {@code dev.mp.send} puts a C2S payload on the real network for the
 * relay and intent packets to receive; {@code dev.mp.relay} shows what the client last received.
 * The packet's row owns this class; dev tools only, never a Foreman or network side effect of their own.
 */
public final class MpDevCommands {
	/** Bound on the received-event ring so {@code dev.mp.relay} cannot grow without limit. */
	static final int EVENT_RING = 32;

	private static final Deque<ReceivedEvent> EVENTS = new ArrayDeque<>();
	private static boolean listening;

	private MpDevCommands() {
	}

	private record ReceivedEvent(StudioId studio, PublicEvent event) {
	}

	public static void register() {
		if (!listening) {
			listening = true;
			// Client-thread listener; the ring is read on the same thread and never written to a log.
			Studios.addEventListener((studio, event) -> {
				synchronized (EVENTS) {
					EVENTS.addLast(new ReceivedEvent(studio, event));
					while (EVENTS.size() > EVENT_RING) {
						EVENTS.removeFirst();
					}
				}
			});
			// The ring holds one connection's events. A mode listener cannot scope it: events injected in
			// singleplayer would survive, because joining or leaving there finds the mode already SINGLEPLAYER.
			// Fabric may fire DISCONNECT on Netty, so both hooks hop to the client thread.
			ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(MpDevCommands::clearEvents));
			ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(MpDevCommands::clearEvents));
		}
		DevBridge.register("dev.mp.send", 10_000,
			"{payload: public_state|studio_event|world_intent|hello, state?|event?|intent?|protocol+modVersion}"
				+ " - send one C2S payload through the real codec (a malformed request is refused here);"
				+ " remote server only (needs AGENTCRAFT_DEV_REMOTE=1)",
			(req, mc) -> {
				CustomPacketPayload packet = parseSend(Fields.of(req));
				return DevBridge.onClient(mc, () -> send(mc, packet));
			});
		DevBridge.register("dev.mp.relay", 10_000,
			"{} - the client's last received states (studios) and events, and the current mode",
			(req, mc) -> DevBridge.onClient(mc, MpDevCommands::relay));
	}

	/** Empty the received-event ring; runs on every play-connection join and disconnect. */
	static void clearEvents() {
		synchronized (EVENTS) {
			EVENTS.clear();
		}
	}

	// ------------------------------------------------------------------ pure parts

	/** Resolve a dev command's optional studio token: absent, empty or "own" is the local studio. */
	public static StudioId resolveStudio(@Nullable String token) {
		if (token == null || token.isBlank() || token.equalsIgnoreCase("own")) {
			return Anchors.self();
		}
		try {
			return StudioId.of(UUID.fromString(token));
		} catch (IllegalArgumentException e) {
			throw new DevException("field 'studio' must be a studio UUID or 'own' (got '" + token + "')");
		}
	}

	/**
	 * Build the payload a {@code dev.mp.send} request names, through the same {@link PublicJson} the
	 * network codecs use, so a malformed payload is refused with the codec's own message.
	 */
	public static CustomPacketPayload parseSend(Fields f) {
		String payload = f.nonBlank("payload");
		try {
			return switch (payload) {
				case "public_state" -> new PublicStateC2S(PublicJson.fromJson(f.obj("state").json()));
				case "studio_event" -> new StudioEventC2S(PublicJson.eventFromJson(f.obj("event").json()));
				case "world_intent" -> new WorldIntentC2S(PublicJson.intentFromJson(f.obj("intent").json()));
				case "hello" -> {
					int protocol = (int) f.integer("protocol", 0, Integer.MAX_VALUE);
					String version = f.str("modVersion");
					yield new HelloC2S(protocol, MpText.sanitize(version.substring(0, Math.min(32, version.length())), 32));
				}
				default -> throw new DevException("field 'payload' must be public_state|studio_event|world_intent|hello (got '" + payload + "')");
			};
		} catch (IllegalArgumentException e) {
			// The network codec reads the record through PublicJson, so this is the codec's refusal.
			throw new DevException("invalid '" + payload + "' payload: " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------ handlers

	/** {@code dev.mp.send} never sends in singleplayer, and on a remote server only with the remote opt-in. */
	public static void checkSend(boolean singleplayer) {
		if (singleplayer) {
			// The integrated server accepts the payload's receiver, but singleplayer takes no multiplayer path.
			throw new DevException("dev.mp.send needs a remote server; singleplayer takes no multiplayer path");
		}
		DevCommands.requireRemoteOptIn();
	}

	private static JsonObject send(Minecraft mc, CustomPacketPayload packet) {
		checkSend(mc.getSingleplayerServer() != null);
		if (mc.getConnection() == null) {
			throw new DevException("dev.mp.send needs a connection to a server");
		}
		if (!ClientPlayNetworking.canSend(packet.type())) {
			throw new DevException("the server does not accept payload '" + packet.type().id() + "'");
		}
		ClientPlayNetworking.send(packet);
		JsonObject o = new JsonObject();
		o.addProperty("type", packet.type().id().toString());
		o.addProperty("sent", true);
		return o;
	}

	private static JsonObject relay() {
		// dev.mp.studios already reports the mode and every studio's latest public state.
		JsonObject out = MpDevFake.studios();
		JsonArray events = new JsonArray();
		synchronized (EVENTS) {
			for (ReceivedEvent e : EVENTS) {
				JsonObject o = PublicJson.toJson(e.event());
				o.addProperty("studio", e.studio().owner().toString());
				events.add(o);
			}
		}
		out.add("events", events);
		return out;
	}

	/** The multiplayer part of {@code dev.state}: mode, studios and the player's own plot. Client thread. */
	public static JsonObject devState() {
		JsonObject out = MpDevFake.studios();
		// One source of truth: the hello (and, with it, MP-03's allocation) feeds Studios; singleplayer has none.
		Optional<Plot> plot = Studios.plot(Anchors.self());
		if (plot.isPresent()) {
			Plot p = plot.get();
			JsonObject o = new JsonObject();
			o.addProperty("index", p.index());
			o.addProperty("x", p.origin().getX());
			o.addProperty("y", p.origin().getY());
			o.addProperty("z", p.origin().getZ());
			out.add("plot", o);
		} else {
			out.add("plot", JsonNull.INSTANCE);
		}
		return out;
	}
}
