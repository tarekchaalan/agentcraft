package dev.agentcraft.mp.server.intent;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.RateBucket;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.WorldIntentC2S;
import dev.agentcraft.mp.state.WorldIntent;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

/**
 * Receives {@link WorldIntentC2S} from mod-equipped players, resolves the sender's plot, rate-limits
 * per player and applies the snapshot inside the plot box only. Rejected intents are logged with a
 * stable reason; unknown block-entity bindings are skipped and logged once per plot (server-wide),
 * without the binding text (it may be a repo id).
 */
public final class WorldIntentFeature {
	/** The rate bucket plus the rate it was built with; rebuilt when the config rate changes. */
	private record PlayerRate(RateBucket bucket, int rate) {}

	private static final Map<UUID, PlayerRate> RATE = new ConcurrentHashMap<>();
	/** Plot indexes that already logged an unknown binding for anyone (survives reconnects). */
	private static final Set<Integer> LOGGED_PLOTS = ConcurrentHashMap.newKeySet();
	/** Caller-supplied time source; tests replace it. */
	static volatile LongSupplier clock = System::nanoTime;

	private WorldIntentFeature() {}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(WorldIntentC2S.TYPE, (payload, context) -> {
			UUID player = context.player().getUUID();
			WorldIntent intent = payload.intent();
			context.server().execute(() -> handle(context.server(), player, intent));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> onPlayerDisconnect(handler.getPlayer().getUUID())));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RATE.clear();
			LOGGED_PLOTS.clear();
			clock = System::nanoTime;
		});
	}

	/** Test seam: replace the clock used by the rate limiter. */
	public static void setClock(LongSupplier value) {
		clock = Objects.requireNonNull(value);
	}

	/** Test seam: forget a player's rate bucket. */
	public static void resetPlayer(UUID player) {
		RATE.remove(player);
	}

	/** Test seam: forget every player's rate bucket and the logged-plot set. */
	public static void resetState() {
		RATE.clear();
		LOGGED_PLOTS.clear();
	}

	/** A disconnect forgets the rate bucket, so a reconnect gets fresh tokens but not a second
	 *  unknown-binding warning for a plot the server already reported. */
	public static void onPlayerDisconnect(UUID player) {
		RATE.remove(player);
	}

	public static void handle(MinecraftServer server, UUID player, WorldIntent intent) {
		if (!server.isDedicatedServer() || !MpServerConfig.current().enabled()) {
			return;
		}
		StudioId studio = StudioId.of(player);
		Optional<Plot> oPlot = Plots.directory().plotOf(studio);
		if (oPlot.isEmpty()) {
			MpLog.event(MpEvents.WORLD_INTENT_REJECTED, "player", player, "studio", player, "rev", intent.rev(),
				"reason", MpReasons.NO_PLOT);
			return;
		}
		Plot plot = oPlot.get();
		int rate = MpServerConfig.current().intentsPerSecond();
		PlayerRate pr = RATE.compute(player, (k, old) -> old == null || old.rate() != rate ? new PlayerRate(new RateBucket(rate), rate) : old);
		if (!pr.bucket().tryTake(clock.getAsLong())) {
			MpLog.event(MpEvents.WORLD_INTENT_REJECTED, "player", player, "studio", player, "plot", plot.index(),
				"rev", intent.rev(), "reason", MpReasons.RATE_LIMITED);
			return;
		}
		int changed = IntentApplier.apply(server.overworld(), plot, studio, intent, LOGGED_PLOTS);
		MpLog.event(MpEvents.WORLD_INTENT_APPLIED, "player", player, "studio", player, "plot", plot.index(),
			"rev", intent.rev(), "changed", changed, "lamps", intent.lamps().size());
	}
}
