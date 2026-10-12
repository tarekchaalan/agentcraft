package dev.agentcraft.client.decisions;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicStudioState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The order in which open decisions are put in front of the user (HUD badge, J key, podium, decision
 * screen): permission prompts first (an agent is blocked mid-tool-call), then questions (planning
 * waits on them), then merges (work is done and can wait), oldest first within a kind.
 */
public final class DecisionQueue {
	private static long cachedRevision = -1;
	private static List<Decision> cached = List.of();

	private DecisionQueue() {
	}

	public static int rank(DecisionKind k) {
		return switch (k) {
			case PERMISSION -> 0;
			case QUESTION -> 1;
			case MERGE -> 2;
			default -> 3;
		};
	}

	/**
	 * The visitor look of the podium: the public open-decision count and the first public agent that
	 * waits for its owner. No question text, no kind and no viewer data.
	 */
	public record RemotePodium(int count, @Nullable String agentId, @Nullable String agentName) {
		public String header() {
			return count == 1 ? "1 decision waiting" : count + " decisions waiting";
		}
	}

	/** The remote model from a public state ({@code null} = the remote state has not arrived yet). */
	public static RemotePodium remote(@Nullable PublicStudioState state) {
		if (state == null) {
			return new RemotePodium(0, null, null);
		}
		PublicAgent awaiting = null;
		for (PublicAgent a : state.agents()) {
			if (a.awaitingUser()) {
				awaiting = a;
				break;
			}
		}
		return new RemotePodium(state.counts().openDecisions(), awaiting == null ? null : awaiting.id(), awaiting == null ? null : awaiting.name());
	}

	/** Open decisions in queue order (client thread; cached per Foreman revision). */
	public static List<Decision> open() {
		ForemanState s = Foreman.state();
		if (s == null) {
			return List.of();
		}
		if (s.revision() != cachedRevision) {
			List<Decision> out = new ArrayList<>(s.openDecisions());
			out.sort(Comparator.comparingInt((Decision d) -> rank(d.kind())).thenComparingLong(Decision::createdAt).thenComparing(Decision::id));
			cached = List.copyOf(out);
			cachedRevision = s.revision();
		}
		return cached;
	}

	public static int count() {
		return open().size();
	}

	public static @Nullable Decision first() {
		List<Decision> l = open();
		return l.isEmpty() ? null : l.get(0);
	}

	public static @Nullable Decision firstOfKind(DecisionKind kind) {
		for (Decision d : open()) {
			if (d.kind() == kind) {
				return d;
			}
		}
		return null;
	}

	public static int indexOf(String decisionId) {
		List<Decision> l = open();
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i).id().equals(decisionId)) {
				return i;
			}
		}
		return -1;
	}

	/** Human label of a kind: "question", "permission", "merge review". */
	public static String kindLabel(DecisionKind k) {
		return switch (k) {
			case PERMISSION -> "permission";
			case MERGE -> "merge review";
			case QUESTION -> "question";
			default -> "decision";
		};
	}
}
