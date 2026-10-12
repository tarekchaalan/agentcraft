package dev.agentcraft.client.hq;

import dev.agentcraft.block.LampStatus;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Repo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Test-only copy of the pre-MP-07 {@code HqWorldDriver.compute(ForemanState)} and its helpers, taken
 * verbatim from {@code mp/integration}, {@code ci:<repoId>} keys included. The golden tests compare
 * the driver's full {@code compute(ForemanState)} with this reference as it is (singleplayer is
 * unchanged), and the wire intent of {@code compute(ForemanState, Layout)} with this reference
 * minus what the wire does not carry.
 */
final class HqWorldDriverReference {
	static final String BEACON_BINDING = "beacon";

	record Reference(Map<String, LampStatus> lamps, boolean podiumOpen, boolean mergeActive, Map<String, Boolean> monitorLit) {}

	private HqWorldDriverReference() {}

	static Reference compute(ForemanState st) {
		Map<String, LampStatus> lamps = new HashMap<>();
		Map<String, Boolean> lit = new HashMap<>();
		Map<String, String> waitingOn = awaiting(st);
		for (Agent a : st.agents().values()) {
			lamps.put("agent:" + a.id(), agentLamp(a, waitingOn.containsKey(a.id())));
			lit.put(a.id(), a.isActive());
		}
		int n = 0;
		for (Repo r : st.repos().values()) {
			LampStatus ci = LampStatus.forCi(r.ci().wire());
			lamps.put("ci:" + r.id(), ci);
			lamps.put("ci:#" + (++n), ci);
		}
		LampStatus goal = goalLamp(st.goal());
		lamps.put("goal", goal);
		lamps.put("goal:atrium", goal);
		boolean open = !st.openDecisions().isEmpty();
		lamps.put("decisions", open ? LampStatus.WAITING : LampStatus.OFF);
		boolean merge = st.oldestOpen(DecisionKind.MERGE) != null;
		lamps.put("merge", merge ? LampStatus.WAITING : LampStatus.OFF);
		lamps.put(BEACON_BINDING, beaconLamp(st, open, goal));
		return new Reference(Map.copyOf(lamps), open, merge, Map.copyOf(lit));
	}

	static Map<String, String> awaiting(ForemanState st) {
		Map<String, String> out = new HashMap<>();
		List<Protocol.Decision> open = st.openDecisions();
		for (Protocol.Decision d : open) {
			if (d.kind() != DecisionKind.MERGE) {
				out.putIfAbsent(d.agentId(), d.id());
			}
		}
		for (Protocol.Decision d : open) {
			out.putIfAbsent(d.agentId(), d.id());
		}
		for (Protocol.Decision d : open) {
			if (d.taskId() != null) {
				Protocol.Task t = st.task(d.taskId());
				if (t != null && t.assignee() != null) {
					out.putIfAbsent(t.assignee(), d.id());
				}
			}
		}
		return out;
	}

	static LampStatus agentLamp(Agent a, boolean awaitingUser) {
		if (!a.isActive()) {
			return LampStatus.OFF;
		}
		String fam = a.state().family();
		if (awaitingUser && (fam.equals("idle") || fam.equals("done"))) {
			return LampStatus.WAITING;
		}
		return LampStatus.forAgentState(a.state().wire());
	}

	static LampStatus beaconLamp(ForemanState st, boolean decisionOpen, LampStatus goal) {
		if (decisionOpen) {
			return LampStatus.WAITING;
		}
		boolean error = false;
		boolean working = false;
		boolean thinking = false;
		boolean waiting = false;
		for (Agent a : st.agents().values()) {
			if (!a.isActive()) {
				continue;
			}
			switch (a.state().family()) {
				case "waiting" -> waiting = true;
				case "error" -> error = true;
				case "working" -> working = true;
				case "thinking" -> thinking = true;
				default -> {
				}
			}
		}
		if (waiting) {
			return LampStatus.WAITING;
		}
		if (error) {
			return LampStatus.ERROR;
		}
		if (working) {
			return LampStatus.WORKING;
		}
		if (thinking) {
			return LampStatus.THINKING;
		}
		return goal == LampStatus.DONE ? LampStatus.DONE : LampStatus.IDLE;
	}

	static LampStatus goalLamp(@Nullable Goal g) {
		if (g == null) {
			return LampStatus.IDLE;
		}
		return switch (g.status()) {
			case PLANNING -> LampStatus.THINKING;
			case ACTIVE -> LampStatus.WORKING;
			case DONE -> LampStatus.DONE;
			case FAILED -> LampStatus.ERROR;
			default -> LampStatus.IDLE;
		};
	}
}