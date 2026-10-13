package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Diff;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Static entry point to the Foreman for every client feature.
 *
 * <pre>
 * Foreman.state().agents()                      // read the model (client thread)
 * Foreman.addListener(new ForemanListener() {...}) // change callbacks (client thread)
 * Foreman.submitGoal("Add OAuth", null).thenAccept(ack -> ...)  // intents; futures complete on the client thread
 * Foreman.requestDiff(repoId, worktree).thenAccept(diff -> ...)
 * </pre>
 *
 * Intents fail fast (exceptionally) while the link is not synced; an {@link Ack} with
 * {@code ok=false} carries the Foreman's error text.
 */
public final class Foreman {
	private static ForemanState state;
	private static ForemanLink link;

	private Foreman() {
	}

	static void install(ForemanState s, ForemanLink l) {
		state = s;
		link = l;
	}

	public static ForemanState state() {
		return state;
	}

	public static ForemanLink link() {
		return link;
	}

	public static void addListener(ForemanListener l) {
		state.addListener(l);
	}

	public static boolean connected() {
		return link != null && link.status().synced();
	}

	/** Send any client message (type + payload); see docs/protocol.md "Mod -> Foreman". */
	public static CompletableFuture<Ack> send(String type, JsonObject payload) {
		JsonObject m = payload.deepCopy();
		m.addProperty("type", type);
		return link.send(m);
	}

	/** New goal for the lead (console: plain text). {@code repoId} null = the Foreman's default repo. */
	public static CompletableFuture<Ack> submitGoal(String text, @Nullable String repoId) {
		return link.send(ForemanJson.msg("goal.submit").put("text", text).put("repoId", repoId).json());
	}

	/** Message an agent ({@code to} = agent id) or everyone ({@code "all"}; a leading "@name" routes it). */
	public static CompletableFuture<Ack> message(String to, String text) {
		return link.send(ForemanJson.msg("user.message").put("to", to).put("text", text).json());
	}

	/** Answer a decision with an option label (preferred) and/or free text. */
	public static CompletableFuture<Ack> answer(String decisionId, @Nullable String option, @Nullable String text) {
		return link.send(ForemanJson.msg("decision.answer").put("decisionId", decisionId).put("option", option).put("text", text).json());
	}

	/** {@code action}: reassign | cancel | retry | prioritize; {@code arg}: agent id / priority. */
	public static CompletableFuture<Ack> taskAction(String taskId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("task.action").put("taskId", taskId).put("action", action).put("arg", arg).json());
	}

	/** {@code action}: pause | resume | stop | spawn; {@code arg}: spawn task id. */
	public static CompletableFuture<Ack> agentAction(String agentId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("agent.action").put("agentId", agentId).put("action", action).put("arg", arg).json());
	}

	/**
	 * Report or change agents' models ({@code agent.model}, the console's /model). {@code agentId}: an agent, "lead",
	 * "workers" or "all" (null: everyone); {@code model} null: only report, "default": back to the configured model;
	 * {@code effort} optional. The ack's result carries {@code agents} ({@link Protocol.AgentModelInfo}[]) and {@code text}
	 * (one line for the player), and for a change {@code saved} (false: config.json could not be written).
	 */
	public static CompletableFuture<Ack> agentModel(@Nullable String agentId, @Nullable String model, @Nullable String effort) {
		return link.send(ForemanJson.msg("agent.model").put("agentId", agentId).put("model", model).put("effort", effort).json());
	}

	public static CompletableFuture<Ack> addRepo(String path) {
		return link.send(ForemanJson.msg("repo.add").put("path", path).json());
	}

	/** Structured diff of a worktree (or an agent id: its current worktree) vs its base. */
	public static CompletableFuture<Diff> requestDiff(String repoId, String worktree) {
		return link.requestDiff(repoId, worktree);
	}
}
