package dev.agentcraft.client.console;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.client.console.ConsoleCommands.AgentModels;
import dev.agentcraft.client.console.ConsoleCommands.Completion;
import dev.agentcraft.client.console.ConsoleCommands.Intent;
import dev.agentcraft.client.console.ConsoleCommands.Invalid;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.foreman.Protocol.AgentModelInfo;
import dev.agentcraft.client.foreman.Protocol.ModelSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The console's /model: parsing, the live preview, Tab completion, the list lines and the ack's agents. */
class ConsoleModelCommandTest {
	/** A mixed team: a Claude lead and worker, a Codex worker, an off-shift worker with no engine reported. */
	private static ForemanState team() {
		JsonArray agents = new JsonArray();
		agents.add(agent("marlow", "Marlow", "lead", "claude", "Opus 5.5"));
		agents.add(agent("juniper", "Juniper", "worker", "claude", "Sonnet 5"));
		agents.add(agent("kit", "Kit", "worker", "codex", "GPT-5.1 Codex"));
		agents.add(agent("wren", "Wren", "worker", null, null));
		JsonObject snapshot = new JsonObject();
		snapshot.add("agents", agents);
		return ForemanStates.fromSnapshot(snapshot);
	}

	private static JsonObject agent(String id, String name, String role, String engine, String model) {
		JsonObject a = new JsonObject();
		a.addProperty("id", id);
		a.addProperty("name", name);
		a.addProperty("role", role);
		a.addProperty("color", "#2E78C6");
		a.addProperty("skin", id);
		a.addProperty("state", "idle");
		a.addProperty("activity", "ready");
		a.addProperty("station", "lounge");
		a.addProperty("paused", false);
		a.addProperty("active", true);
		if (engine != null) {
			a.addProperty("engine", engine);
		}
		if (model != null) {
			a.addProperty("model", model);
		}
		return a;
	}

	private static Intent parse(String text) {
		return ConsoleCommands.parse(text, team());
	}

	private static String error(String text) {
		Intent in = parse(text);
		assertInstanceOf(Invalid.class, in, text);
		return ((Invalid) in).error();
	}

	@Test
	void lists_shows_and_changes_with_any_spelling_of_the_agent_and_the_aliases_in_lower_case() {
		assertEquals(new AgentModels(null, null, null), parse("/model"));
		assertEquals(new AgentModels(null, null, null), parse("/models"));
		assertEquals(new AgentModels("juniper", null, null), parse("/model Juniper"));
		assertEquals(new AgentModels("juniper", "sonnet", null), parse("/model juniper sonnet"));
		assertEquals(new AgentModels("juniper", "sonnet", "high"), parse("/model @JUNIPER Sonnet HIGH"));
		assertEquals(new AgentModels("juniper", "haiku", null), parse("/model ju haiku")); // a unique prefix, like @mentions
		assertEquals(new AgentModels("lead", "opus", null), parse("/model foreman opus"));
		assertEquals(new AgentModels("lead", null, null), parse("/model lead"));
		assertEquals(new AgentModels("workers", "fable", "max"), parse("/model workers fable max"));
		assertEquals(new AgentModels("all", "default", null), parse("/model all default"));
		assertEquals(new AgentModels("juniper", "default", null), parse("/model juniper DEFAULT"));
		// full ids as typed; a Codex agent takes its own models and efforts
		assertEquals(new AgentModels("juniper", "claude-sonnet-5", "xhigh"), parse("/model juniper claude-sonnet-5 xhigh"));
		assertEquals(new AgentModels("kit", "gpt-5.1-codex", "minimal"), parse("/model kit gpt-5.1-codex minimal"));
		assertEquals(new AgentModels("kit", "default", null), parse("/model kit default"));
		// no engine known (the sim, an older Foreman): the Foreman decides
		assertEquals(new AgentModels("wren", "gpt-5", null), parse("/model wren gpt-5"));
		assertEquals(new AgentModels("workers", "gpt-5", null), parse("/model workers gpt-5"));
	}

	@Test
	void refuses_what_the_agent_cannot_run_and_says_why_before_enter() {
		assertEquals("Kit runs on Codex: sonnet is a Claude model (a Codex model, or default)", error("/model kit sonnet"));
		assertEquals("Kit runs on Codex: claude-opus-5-5 is a Claude model (a Codex model, or default)", error("/model kit claude-opus-5-5"));
		assertEquals("Juniper runs on Claude: gpt-5 is not a Claude model (opus, sonnet, haiku, fable or a claude-... id)", error("/model juniper gpt-5"));
		assertEquals("Marlow runs on Claude: sonet is not a Claude model (opus, sonnet, haiku, fable or a claude-... id)", error("/model lead sonet"));
		assertEquals("no agent named bob (an agent, lead, workers or all)", error("/model bob sonnet"));
		assertEquals("high is an effort: give the model first, /model juniper <model> high", error("/model juniper high"));
		assertEquals("'extreme' is not an effort: low, medium, high, xhigh, max", error("/model juniper sonnet extreme"));
		assertEquals("'turbo' is not an effort: none, minimal, low, medium, high, xhigh", error("/model kit gpt-5 turbo"));
		assertEquals("default goes back to the configured model and effort: give no effort with it", error("/model juniper default high"));
		assertEquals("'son;net' is not a model name (opus, sonnet, haiku, fable or a model id)", error("/model juniper son;net"));
		assertTrue(error("/model juniper sonnet high now").startsWith("usage: /model [agent] [model] [effort]"));
	}

	@Test
	void the_preview_says_what_enter_will_do() {
		ForemanState s = team();
		assertEquals("show every agent's model", ConsoleCommands.describe(parse("/model"), s));
		assertEquals("show the workers' models", ConsoleCommands.describe(parse("/model workers"), s));
		assertEquals("show Juniper's model", ConsoleCommands.describe(parse("/model juniper"), s));
		assertEquals("Juniper → sonnet (high), saved", ConsoleCommands.describe(parse("/model juniper sonnet high"), s));
		assertEquals("Marlow → opus, saved", ConsoleCommands.describe(parse("/model foreman opus"), s));
		assertEquals("every worker → the configured model, saved", ConsoleCommands.describe(parse("/model workers default"), s));
		assertEquals("everyone → haiku, saved", ConsoleCommands.describe(parse("/model all haiku"), s));
		Map<String, Object> m = ConsoleCommands.toMap(parse("/model juniper sonnet high"));
		assertEquals(Map.of("intent", "agentmodels", "target", "juniper", "model", "sonnet", "effort", "high"), Map.copyOf(m));
	}

	@Test
	void tab_completes_the_command_the_agents_and_groups_the_models_its_engine_runs_and_the_efforts() {
		ForemanState s = team();
		assertTrue(labels(ConsoleCommands.complete("/mo", 3, s)).contains("/model"));
		List<Completion> who = ConsoleCommands.complete("/model ", 7, s);
		assertEquals(List.of("Marlow", "Juniper", "Kit", "Wren", "lead", "workers", "all"), labels(who));
		assertEquals("juniper ", who.get(1).replacement());
		assertEquals("Sonnet 5", who.get(1).detail()); // the agent's model badge
		assertEquals(List.of("Juniper"), labels(ConsoleCommands.complete("/model @ju", 10, s)));
		assertEquals(List.of("workers"), labels(ConsoleCommands.complete("/model wo", 9, s)));
		assertEquals(List.of("opus", "sonnet", "haiku", "fable", "default"), labels(ConsoleCommands.complete("/model juniper ", 15, s)));
		assertEquals(List.of("sonnet"), labels(ConsoleCommands.complete("/model juniper s", 16, s)));
		assertEquals(List.of("default"), labels(ConsoleCommands.complete("/model kit ", 11, s))); // Codex: no Claude aliases
		assertEquals(List.of("low", "medium", "high", "xhigh", "max"), labels(ConsoleCommands.complete("/model juniper sonnet ", 22, s)));
		assertEquals(List.of("high"), labels(ConsoleCommands.complete("/model juniper sonnet h", 23, s)));
		assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh"), labels(ConsoleCommands.complete("/model kit gpt-5 ", 17, s)));
		assertTrue(ConsoleCommands.complete("/model juniper default ", 23, s).isEmpty());
	}

	@Test
	void reads_the_agents_of_the_answer_and_writes_one_line_each() {
		JsonObject result = JsonParser.parseString("""
			{"text": "x", "agents": [
			  {"agentId": "marlow", "name": "Marlow", "engine": "claude", "model": "claude-fable-5-1", "label": "Fable 5.1", "effort": "high",
			   "source": "config", "fallback": "Opus 5.5 (xhigh)"},
			  {"agentId": "juniper", "name": "Juniper", "engine": "claude", "model": "sonnet", "label": "Sonnet 5", "effort": "high", "source": "override"},
			  {"agentId": "kit", "name": "Kit", "engine": "codex", "model": "default", "label": "Codex default", "source": "default"},
			  {"agentId": "tove", "name": "Tove", "engine": "claude", "model": "opus", "label": "", "source": "a-newer-source"},
			  "not an agent"
			]}""").getAsJsonObject();
		List<AgentModelInfo> agents = ConsoleCommands.modelInfos(result);
		assertEquals(List.of("marlow", "juniper", "kit", "tove"), agents.stream().map(AgentModelInfo::agentId).toList());
		assertEquals(ModelSource.CONFIG, agents.get(0).source());
		assertEquals(ModelSource.UNKNOWN, agents.get(3).source());
		assertEquals("opus", agents.get(3).label()); // no label: the model as configured
		assertEquals("Marlow · Fable 5.1 (high) · configured · now on Opus 5.5 (xhigh) while its model is at its limit",
			ConsoleCommands.modelLine(agents.get(0)));
		assertEquals("Juniper · Sonnet 5 (high) · set with /model", ConsoleCommands.modelLine(agents.get(1)));
		assertEquals("Kit · Codex: Codex default · default", ConsoleCommands.modelLine(agents.get(2)));
		assertTrue(ConsoleCommands.modelInfos(null).isEmpty());
		assertTrue(ConsoleCommands.modelInfos(new JsonObject()).isEmpty());
	}

	@Test
	void is_in_help_and_sends_to_the_foreman() {
		assertTrue(ConsoleCommands.COMMANDS.stream().anyMatch(c -> c.name().equals("model") && c.usage().equals("/model [agent] [model] [effort]")));
		assertTrue(ConsoleCommands.isClaudeModel("Opus[1m]"));
		assertTrue(ConsoleCommands.isClaudeModel("us.anthropic.claude-haiku-4-5-20251001-v1:0"));
		assertFalse(ConsoleCommands.isClaudeModel("gpt-5.1-codex"));
	}

	private static List<String> labels(List<Completion> cs) {
		return cs.stream().map(Completion::label).toList();
	}
}
