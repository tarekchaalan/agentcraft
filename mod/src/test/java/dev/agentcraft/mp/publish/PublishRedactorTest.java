package dev.agentcraft.mp.publish;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.agentcraft.client.agents.AgentManager;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.publish.Redactor;
import dev.agentcraft.mp.net.PublicStateC2S;
import dev.agentcraft.mp.net.StudioEventC2S;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.PublicTask;
import dev.agentcraft.mp.state.StationWire;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class PublishRedactorTest {
    @Test void flags_off_showcase_bytes_contain_no_private_text() {
        ForemanState state = ForemanStates.showcase();
        Secrets secrets = Secrets.of(state);
        String published = published(Redactor.redact(state, PublicPolicy.DEFAULT));
        secrets.assertHidden(state, published);
        assertNull(Redactor.redact(state, PublicPolicy.DEFAULT).tasks());
        for (PublicAgent agent : Redactor.redact(state, PublicPolicy.DEFAULT).agents()) assertNull(agent.activity());
        assertNull(Redactor.redact(state, PublicPolicy.DEFAULT).goal().text());
    }

    @Test void marker_scan_catches_private_values_after_public_clipping_and_each_flag_adds_only_its_marker() {
        ForemanState state = markedState();
        List<String> markers = List.of("ZQ1", "ZQ2", "ZQ3", "ZQ4", "ZQ5", "ZQ6", "ZQ7", "ZQ8", "ZQ9");
        PublicStudioState hidden = Redactor.redact(state, PublicPolicy.DEFAULT);
        String hiddenStateJson = PublicJson.toJson(hidden).toString();
        String hiddenStatePacket = new String(stateBytes(hidden), StandardCharsets.UTF_8);
        PublicEvent hiddenSay = Redactor.say(state, new Protocol.AgentSay("ada", "ZQ4 private say", "user", 1), PublicPolicy.DEFAULT);
        String hiddenEventPacket = new String(eventBytes(hiddenSay), StandardCharsets.UTF_8);
        assertMarkersHidden(markers, hiddenStateJson + hiddenStatePacket + hiddenEventPacket);

        List<PublicPolicy> policies = List.of(new PublicPolicy(true, false, false, false),
            new PublicPolicy(false, false, true, false), new PublicPolicy(false, false, false, true));
        List<String> flagMarkers = List.of("ZQ1", "ZQ2", "ZQ3");
        for (int i = 0; i < policies.size(); i++) {
            PublicStudioState projection = Redactor.redact(state, policies.get(i));
            String publicBytes = PublicJson.toJson(projection) + new String(stateBytes(projection), StandardCharsets.UTF_8);
            assertEquals(flagMarkers.get(i), markerIn(publicBytes, markers));
        }

        PublicEvent.Say shownSay = (PublicEvent.Say) Redactor.say(state,
            new Protocol.AgentSay("ada", "ZQ4 private say", "user", 1), new PublicPolicy(false, true, false, false));
        assertEquals("ZQ4", markerIn(new String(eventBytes(shownSay), StandardCharsets.UTF_8), markers));

        ForemanState clipped = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"agents":[{"id":"ada","name":"Ada","skin":"ada","state":"idle","station":"desk","activity":"ZQ1%s"}]}
            """.formatted("x".repeat(80))).getAsJsonObject());
        String clippedOutput = PublicJson.toJson(Redactor.redact(clipped, policies.getFirst())).toString();
        assertTrue(clippedOutput.contains("ZQ1"));
        assertThrows(AssertionFailedError.class, () -> assertMarkersHidden(markers, clippedOutput));
    }

    @Test void activity_flag_adds_only_activity() {
        ForemanState state = ForemanStates.showcase();
        Secrets secrets = Secrets.of(state);
        PublicPolicy policy = new PublicPolicy(true, false, false, false);
        PublicStudioState published = Redactor.redact(state, policy);
        String json = published(published);
        assertTrue(json.contains(secrets.activity), secrets.activity);
        assertFalse(json.contains(secrets.title), secrets.title);
        assertFalse(json.contains(secrets.goal), secrets.goal);
        assertFalse(json.contains(secrets.question), secrets.question);
        assertNull(published.tasks());
        assertNull(published.goal().text());
        assertTrue(published.agents().stream().anyMatch(agent -> secrets.activity.equals(agent.activity())));
        assertTrue(published.agents().stream().allMatch(agent -> agent.activity() == null || secrets.activities.contains(agent.activity())));
    }

    @Test void task_flag_adds_only_task_titles() {
        ForemanState state = ForemanStates.showcase();
        Secrets secrets = Secrets.of(state);
        PublicPolicy policy = new PublicPolicy(false, false, true, false);
        PublicStudioState published = Redactor.redact(state, policy);
        String json = published(published);
        assertTrue(json.contains(secrets.title), secrets.title);
        assertFalse(json.contains(secrets.activity), secrets.activity);
        assertFalse(json.contains(secrets.goal), secrets.goal);
        assertFalse(json.contains(secrets.question), secrets.question);
        assertNotNull(published.tasks());
        assertTrue(published.tasks().stream().anyMatch(task -> secrets.title.equals(task.title())));
        assertTrue(published.agents().stream().allMatch(agent -> agent.activity() == null));
        assertNull(published.goal().text());
    }

    @Test void goal_flag_adds_only_goal_text() {
        ForemanState state = ForemanStates.showcase();
        Secrets secrets = Secrets.of(state);
        PublicPolicy policy = new PublicPolicy(false, false, false, true);
        PublicStudioState published = Redactor.redact(state, policy);
        String json = published(published);
        assertTrue(json.contains(secrets.goal), secrets.goal);
        assertFalse(json.contains(secrets.activity), secrets.activity);
        assertFalse(json.contains(secrets.title), secrets.title);
        assertFalse(json.contains(secrets.question), secrets.question);
        assertEquals(secrets.goal, published.goal().text());
        assertNull(published.tasks());
        assertTrue(published.agents().stream().allMatch(agent -> agent.activity() == null));
    }

    @Test void say_flag_adds_only_say_text() {
        ForemanState state = ForemanStates.showcase();
        Protocol.AgentSay say = new Protocol.AgentSay("marlow", "SAY_OPT_IN_TEXT", "user", 1L);
        PublicEvent.Say hidden = (PublicEvent.Say) Redactor.say(state, say, PublicPolicy.DEFAULT);
        PublicEvent.Say shown = (PublicEvent.Say) Redactor.say(state, say, new PublicPolicy(false, true, false, false));
        assertNull(hidden.text());
        assertEquals(say.text().length(), hidden.length());
        assertEquals("SAY_OPT_IN_TEXT", shown.text());
        assertEquals("user", shown.to());
        String stateJson = published(Redactor.redact(state, new PublicPolicy(false, true, false, false)));
        assertFalse(stateJson.contains("SAY_OPT_IN_TEXT"));
        assertFalse(published(Redactor.redact(state, PublicPolicy.DEFAULT)).contains("SAY_OPT_IN_TEXT"));
        assertTrue(new String(eventBytes(shown), StandardCharsets.UTF_8).contains("SAY_OPT_IN_TEXT"));
        assertFalse(new String(eventBytes(hidden), StandardCharsets.UTF_8).contains("SAY_OPT_IN_TEXT"));
    }

    @Test void all_flags_still_hide_repo_question_log_and_path() {
        ForemanState state = ForemanStates.showcase();
        Secrets secrets = Secrets.of(state);
        PublicPolicy policy = new PublicPolicy(true, true, true, true);
        String json = published(Redactor.redact(state, policy));
        assertFalse(json.contains(secrets.repoId), secrets.repoId);
        assertFalse(json.contains(secrets.path), secrets.path);
        assertFalse(json.contains(secrets.question), secrets.question);
        assertFalse(json.contains(secrets.log), secrets.log);
        assertTrue(json.contains(secrets.activity));
        assertTrue(json.contains(secrets.title));
        assertTrue(json.contains(secrets.goal));
    }

    @Test void awaiting_user_matches_agent_manager_on_showcase() {
        ForemanState state = ForemanStates.showcase();
        Set<String> waiting = new HashSet<>();
        for (Protocol.Decision decision : state.openDecisions()) waiting.add(AgentManager.owner(state, decision));
        PublicStudioState published = Redactor.redact(state, PublicPolicy.DEFAULT);
        assertFalse(waiting.isEmpty());
        for (PublicAgent agent : published.agents()) assertEquals(waiting.contains(agent.id()), agent.awaitingUser(), agent.id());
        assertTrue(published.agents().stream().anyMatch(PublicAgent::awaitingUser));
        assertTrue(published.agents().stream().anyMatch(agent -> !agent.awaitingUser()));
        assertTrue(waiting.contains("wren"));
        assertTrue(waiting.contains("marlow"));
        assertFalse(waiting.contains("juniper"));
    }

    @Test void counts_include_every_open_decision_and_the_merge_subset() {
        ForemanState state = ForemanStates.showcase();
        int merges = 0;
        for (Protocol.Decision decision : state.openDecisions()) if (decision.kind() == Protocol.DecisionKind.MERGE) merges++;
        var counts = Redactor.redact(state, PublicPolicy.DEFAULT).counts();
        assertEquals(state.openDecisions().size(), counts.openDecisions());
        assertEquals(merges, counts.openMerges());
        assertTrue(counts.openDecisions() > counts.openMerges());
        assertTrue(merges > 0);
    }

    @Test void unknown_values_are_mapped_and_unknown_tasks_are_omitted() {
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"agents":[
              {"id":"ada","name":"Ada","skin":"ada","state":"not-a-state","station":"not-a-station","activity":"secret activity"},
              {"id":"bea","name":"Bea","skin":"bea","state":"editing","station":"library","activity":"   "}
            ],"tasks":[
              {"id":"t-unknown","title":"hidden unknown title","status":"not-a-status"},
              {"id":"t-todo","title":"visible todo title","status":"todo","assignee":"ada"},
              {"id":"t-cancel","title":"visible cancelled title","status":"cancelled","assignee":"not-an-agent"}
            ],"decisions":[
              {"id":"q","agentId":"ada","kind":"question","question":"secret question","status":"open","taskId":"t-todo"},
              {"id":"m","agentId":"bea","kind":"merge","question":"secret merge","status":"open","taskId":"t-todo"}
            ],"goal":{"id":"g","text":"secret goal","progress":2.5,"status":"not-a-status"}}
            """).getAsJsonObject());
        PublicPolicy policy = new PublicPolicy(true, false, true, true);
        PublicStudioState published = Redactor.redact(state, policy);
        assertEquals(AgentStateWire.IDLE, agent(published, "ada").state());
        assertEquals(StationWire.DESK, agent(published, "ada").station());
        assertEquals("secret activity", agent(published, "ada").activity());
        assertNull(agent(published, "bea").activity());
        assertTrue(agent(published, "ada").awaitingUser());
        assertFalse(agent(published, "bea").awaitingUser());
        assertEquals(GoalStatusWire.NONE, published.goal().status());
        assertEquals(0f, published.goal().progress());
        assertNull(published.goal().text());
        assertEquals(List.of("t-todo", "t-cancel"), published.tasks().stream().map(task -> task.id()).toList());
        assertNull(published.tasks().get(1).assignee());
        assertEquals("ada", published.tasks().get(0).assignee());
        assertEquals(1, published.counts().todo());
        assertEquals(0, published.counts().doing() + published.counts().review() + published.counts().done() + published.counts().blocked());
        assertEquals(2, published.counts().openDecisions());
        assertEquals(1, published.counts().openMerges());
        String json = published(published);
        assertFalse(json.contains("hidden unknown title"));
        assertFalse(json.contains("secret goal"));
        assertFalse(json.contains("secret question"));
        assertFalse(json.contains("secret merge"));
        assertFalse(json.contains("not-an-agent"));
    }

    @Test void blank_opt_in_text_is_null_and_over_cap_text_is_clipped() {
        String activity = "X".repeat(60) + "§c tail";
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"agents":[
              {"id":"ada","name":"Ada","skin":"ada","state":"idle","station":"desk","activity":"%s"},
              {"id":"bea","name":"Bea","skin":"bea","state":"idle","station":"desk","activity":"   "},
              {"id":"cy","name":"Cy","skin":"cy","state":"idle","station":"desk","activity":"§c"}
            ],"goal":{"id":"g","text":"%s","progress":-3,"status":"active"}}
            """.formatted(activity, "Y".repeat(130))).getAsJsonObject());
        PublicPolicy policy = new PublicPolicy(true, false, false, true);
        PublicStudioState published = Redactor.redact(state, policy);
        assertEquals(48, agent(published, "ada").activity().length());
        assertFalse(agent(published, "ada").activity().contains("§"));
        assertNull(agent(published, "bea").activity());
        assertNull(agent(published, "cy").activity());
        assertEquals(120, published.goal().text().length());
        assertEquals(0f, published.goal().progress());
        assertEquals(GoalStatusWire.ACTIVE, published.goal().status());
        assertEquals(published, roundTrip(published));
    }

    @Test void task_title_blank_after_clipping_and_sanitizing_is_omitted_without_changing_counts() {
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"tasks":[
              {"id":"t-space","title":"   ","status":"todo"},
              {"id":"t-format","title":"§c","status":"todo"},
              {"id":"t-visible","title":"Visible","status":"todo"},
              {"id":"t-unknown","title":"Unknown","status":"not-a-status"}]}
            """).getAsJsonObject());
        PublicStudioState published = Redactor.redact(state, new PublicPolicy(false, false, true, false));
        assertEquals(List.of("t-visible"), published.tasks().stream().map(PublicTask::id).toList());
        assertEquals(3, published.counts().todo());
    }

    @Test void task_limit_keeps_only_the_first_32_publishable_tasks() {
        StringBuilder tasks = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i > 0) tasks.append(',');
            tasks.append("{\"id\":\"t-").append(i).append("\",\"title\":\"Title ").append(i)
                .append("\",\"status\":\"todo\"}");
        }
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("{\"tasks\":[" + tasks + "]}").getAsJsonObject());
        PublicStudioState published = Redactor.redact(state, new PublicPolicy(false, false, true, false));
        assertEquals(32, published.tasks().size());
        assertEquals("t-31", published.tasks().getLast().id());
        assertFalse(published.tasks().stream().anyMatch(task -> task.id().equals("t-32")));
        assertEquals(33, published.counts().todo());
    }

    @Test void say_text_is_clipped_to_120_but_length_is_the_original_length() {
        ForemanState state = ForemanStates.showcase();
        String text = "Z".repeat(140);
        PublicEvent.Say say = (PublicEvent.Say) Redactor.say(state,
            new Protocol.AgentSay("marlow", text, "user", 1), new PublicPolicy(false, true, false, false));
        assertEquals(120, say.text().length());
        assertEquals(text.substring(0, 120), say.text());
        assertEquals(140, say.length());
    }

    @Test void goal_progress_is_finite_bounded_and_absent_goal_maps_to_none() {
        var nonFiniteJson = JsonParser.parseString("{}").getAsJsonObject();
        var goal = new com.google.gson.JsonObject();
        goal.addProperty("id", "g");
        goal.addProperty("text", "secret");
        goal.addProperty("progress", Double.NaN);
        goal.addProperty("status", "active");
        nonFiniteJson.add("goal", goal);
        PublicStudioState nonFinite = Redactor.redact(ForemanStates.fromSnapshot(nonFiniteJson), PublicPolicy.DEFAULT);
        assertEquals(0f, nonFinite.goal().progress());
        assertTrue(Float.isFinite(nonFinite.goal().progress()));

        var aboveOneJson = JsonParser.parseString("{}").getAsJsonObject();
        var aboveOneGoal = new com.google.gson.JsonObject();
        aboveOneGoal.addProperty("id", "g");
        aboveOneGoal.addProperty("text", "secret");
        aboveOneGoal.addProperty("progress", Double.MAX_VALUE);
        aboveOneGoal.addProperty("status", "active");
        aboveOneJson.add("goal", aboveOneGoal);
        ForemanState aboveOneState = ForemanStates.fromSnapshot(aboveOneJson);
        PublicStudioState aboveOne = Redactor.redact(aboveOneState, PublicPolicy.DEFAULT);
        assertEquals(1f, aboveOne.goal().progress());

        PublicStudioState noGoal = Redactor.redact(ForemanStates.fromSnapshot(JsonParser.parseString("{}").getAsJsonObject()), PublicPolicy.DEFAULT);
        assertEquals(GoalStatusWire.NONE, noGoal.goal().status());
        assertEquals(0f, noGoal.goal().progress());
        assertNull(noGoal.goal().text());
    }

    @Test void caps_drop_the_extra_agent_repo_and_duplicate_clipped_id() {
        StringBuilder agents = new StringBuilder();
        StringBuilder repos = new StringBuilder();
        for (int i = 0; i < 17; i++) agents.append(i == 0 ? "" : ",").append("{\"id\":\"agent-%02d\",\"name\":\"N%d\",\"skin\":\"s%d\",\"state\":\"idle\",\"station\":\"desk\",\"activity\":\"\"}".formatted(i, i, i));
        for (int i = 0; i < 9; i++) repos.append(i == 0 ? "" : ",").append("{\"id\":\"repo-secret-%d\",\"name\":\"repo-secret-%d\",\"path\":\"/srv/secret-%d\",\"branch\":\"main\",\"ci\":\"pass\"}".formatted(i, i, i));
        String shared = "a".repeat(16);
        agents.insert(0, "{\"id\":\"" + shared + "y\",\"name\":\"Two\",\"skin\":\"two\",\"state\":\"idle\",\"station\":\"desk\",\"activity\":\"\"},");
        agents.insert(0, "{\"id\":\"" + shared + "x\",\"name\":\"One\",\"skin\":\"one\",\"state\":\"idle\",\"station\":\"desk\",\"activity\":\"\"},");
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString(
            "{\"agents\":[" + agents + "],\"repos\":[" + repos + "]}").getAsJsonObject());
        PublicStudioState published = Redactor.redact(state, PublicPolicy.DEFAULT);
        assertEquals(16, published.agents().size());
        assertFalse(published.agents().stream().anyMatch(agent -> agent.id().equals("agent-16")));
        assertEquals(8, published.ci().size());
        assertEquals(0, published.ci().get(0).slot());
        assertEquals(7, published.ci().get(7).slot());
        String json = published(published);
        assertFalse(json.contains("agent-16"));
        assertFalse(json.contains("repo-secret-8"));
        assertFalse(json.contains("/srv/secret-0"));
        assertEquals(1, published.agents().stream().filter(agent -> agent.id().equals(shared)).count());
        assertEquals(published, roundTrip(published));
    }

    @Test void seventeen_agents_thirty_three_tasks_and_over_long_texts_stay_inside_every_wire_cap() {
        String wide = "\"\\".repeat(100); // over every cap, and each character doubles when JSON escapes it
        var agents = new com.google.gson.JsonArray();
        var tasks = new com.google.gson.JsonArray();
        for (int i = 0; i < 33; i++) {
            var row = new com.google.gson.JsonObject();
            for (String key : List.of("name", "skin", "activity", "title")) row.addProperty(key, wide);
            row.addProperty("id", "%02d".formatted(i) + wide);
            row.addProperty("state", "waiting_user");
            row.addProperty("station", "mergestation");
            row.addProperty("status", "cancelled");
            row.addProperty("assignee", "00" + "x".repeat(100));
            tasks.add(row);
            if (i < 17) agents.add(row.deepCopy()); // an agent id has to be an identifier path, a task id does not
            if (i < 17) agents.get(i).getAsJsonObject().addProperty("id", "%02d".formatted(i) + "x".repeat(100));
        }
        var snapshot = JsonParser.parseString("{\"goal\":{\"id\":\"g\",\"status\":\"active\",\"progress\":0.5}}").getAsJsonObject();
        snapshot.getAsJsonObject("goal").addProperty("text", wide);
        snapshot.add("agents", agents);
        snapshot.add("tasks", tasks);
        PublicStudioState published = Redactor.redact(ForemanStates.fromSnapshot(snapshot), new PublicPolicy(true, true, true, true));
        assertEquals(16, published.agents().size());
        assertEquals(32, published.tasks().size());
        assertTrue(published.agents().stream().allMatch(agent -> agent.id().length() == 16 && agent.name().length() == 16
            && agent.skin().length() == 16 && agent.activity().length() == 48));
        assertTrue(published.tasks().stream().allMatch(task -> task.id().length() == 48 && task.title().length() == 80
            && task.assignee().length() == 16));
        assertEquals(120, published.goal().text().length());
        assertTrue(PublicJson.toJson(published).toString().length() < 30000); // the state envelope
        assertEquals(published, roundTrip(published));
    }

    @Test void agent_whose_id_is_not_an_identifier_path_is_left_out_with_everything_that_names_it() {
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"agents":[{"id":"Wren Smith","name":"Wren","skin":"wren","state":"idle","station":"desk","activity":""},
              {"id":"Kit!","name":"Kit","skin":"kit","state":"idle","station":"desk","activity":""},
              {"id":"rowan","name":"Rowan","skin":"rowan","state":"idle","station":"desk","activity":""}],
             "tasks":[{"id":"t1","title":"One","status":"todo","assignee":"Wren Smith"},
              {"id":"t2","title":"Two","status":"todo","assignee":"Kit!"},
              {"id":"t3","title":"Three","status":"todo","assignee":"rowan"}]}
            """).getAsJsonObject());
        PublicPolicy all = new PublicPolicy(true, true, true, true);
        PublicStudioState published = Redactor.redact(state, all);
        assertEquals(List.of("rowan"), published.agents().stream().map(PublicAgent::id).toList());
        assertEquals(java.util.Arrays.asList(null, null, "rowan"), published.tasks().stream().map(PublicTask::assignee).toList());
        for (String id : List.of("Wren Smith", "Kit!")) {
            assertNull(Redactor.say(state, new Protocol.AgentSay(id, "hello", "user", 1), all));
            assertNull(Redactor.taskDone(state, null, task("t-done", Protocol.TaskStatus.DONE, id)));
            assertNull(((PublicEvent.Say) Redactor.say(state, new Protocol.AgentSay("rowan", "hello", id, 1), all)).to());
            assertFalse(published(published).contains(id));
        }
        assertNotNull(Redactor.taskDone(state, null, task("t-done", Protocol.TaskStatus.DONE, "rowan")));
        assertEquals(published, roundTrip(published));
    }

    @Test void skin_that_is_not_an_identifier_path_becomes_the_agent_id_and_fixture_agents_are_unchanged() {
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"agents":[{"id":"wren","name":"Wren","skin":"Wren","state":"idle","station":"desk","activity":""},
              {"id":"guest","name":"Guest","skin":"my skin.png","state":"idle","station":"desk","activity":""}]}
            """).getAsJsonObject());
        assertEquals(List.of("wren", "guest"), Redactor.redact(state, PublicPolicy.DEFAULT).agents().stream().map(PublicAgent::skin).toList());
        for (ForemanState fixture : List.of(ForemanStates.showcase(), ForemanStates.showcaseLate())) {
            assertFalse(fixture.agents().isEmpty());
            assertEquals(fixture.agents().values().stream().map(agent -> List.of(agent.id(), agent.name(), agent.skin())).toList(),
                Redactor.redact(fixture, PublicPolicy.DEFAULT).agents().stream().map(agent -> List.of(agent.id(), agent.name(), agent.skin())).toList());
        }
    }

    @Test void task_or_agent_whose_public_id_is_already_published_is_left_out() {
        String shared = "t".repeat(48);
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("""
            {"agents":[{"id":"kit§c","name":"First","skin":"kit","state":"idle","station":"desk","activity":""},
              {"id":"kit","name":"Second","skin":"kit","state":"idle","station":"desk","activity":""}],
             "tasks":[{"id":"%1$sx","title":"Cut one","status":"todo"},{"id":"%1$sy","title":"Cut two","status":"todo"},
              {"id":"s§c1","title":"Clean one","status":"todo","assignee":"kit"},{"id":"s1","title":"Clean two","status":"todo"}]}
            """.formatted(shared)).getAsJsonObject());
        PublicStudioState published = Redactor.redact(state, new PublicPolicy(false, false, true, false));
        assertEquals(List.of("First"), published.agents().stream().map(PublicAgent::name).toList());
        assertEquals(List.of(shared, "s1"), published.tasks().stream().map(PublicTask::id).toList());
        assertEquals(List.of("Cut one", "Clean one"), published.tasks().stream().map(PublicTask::title).toList());
        assertNull(published.tasks().getLast().assignee()); // the second "kit" is not published, so nothing names it
        assertEquals(4, published.counts().todo());
        assertEquals(published, roundTrip(published));
    }

    @Test void link_down_publishes_last_agents_as_offline() throws Exception {
        ForemanState state = ForemanStates.showcase();
        var setLink = ForemanState.class.getDeclaredMethod("setLink", LinkStatus.class);
        setLink.setAccessible(true);
        setLink.invoke(state, new LinkStatus(LinkStatus.Phase.WAITING_RETRY, "", 1, null, 0, 0, true));
        PublicStudioState published = Redactor.redact(state, PublicPolicy.DEFAULT);
        assertFalse(published.foremanOnline());
        assertFalse(state.link().synced());
        assertEquals(state.agents().size(), published.agents().size());
        assertTrue(state.hasData());
    }

    @Test void say_to_is_kept_only_for_user_or_a_published_agent() {
        ForemanState state = ForemanStates.showcase();
        assertEquals("user", sayTo(state, "user"));
        assertEquals("wren", sayTo(state, "wren"));
        assertNull(sayTo(state, "all")); // the Foreman's "everyone": the contract has no such addressee
        assertNull(sayTo(state, "not-an-agent"));
        assertNull(sayTo(state, "/srv/secret-path"));
        assertNull(Redactor.say(state, new Protocol.AgentSay("not-an-agent", "SECRET_SAY", "user", 1), new PublicPolicy(false, true, false, false)));
    }

    @Test void say_to_all_or_to_an_agent_past_the_cap_is_not_published_even_when_an_agent_has_the_id_all() {
        StringBuilder agents = new StringBuilder();
        List<String> ids = new ArrayList<>(List.of("kit", "all"));
        for (int i = 2; i < 17; i++) ids.add("agent-%02d".formatted(i));
        for (String id : ids) agents.append(agents.isEmpty() ? "" : ",")
            .append("{\"id\":\"%1$s\",\"name\":\"%1$s\",\"skin\":\"%1$s\",\"state\":\"idle\",\"station\":\"desk\",\"activity\":\"\"}".formatted(id));
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("{\"agents\":[" + agents + "]}").getAsJsonObject());
        assertEquals(ids.subList(0, 16), Redactor.redact(state, PublicPolicy.DEFAULT).agents().stream().map(PublicAgent::id).toList());
        assertEquals("agent-15", sayTo(state, "agent-15"));
        assertNull(sayTo(state, "agent-16")); // the 17th agent is not published
        assertNull(sayTo(state, "all")); // "all" from the Foreman means everyone, never the agent with that id
    }

    @Test void say_to_an_agent_whose_public_id_is_a_reserved_word_is_not_published() {
        // The published id is the sanitized one: "all§c" goes out as "all" and "user§c" as "user".
        String agents = "";
        for (String id : List.of("kit", "all\u00a7c", "user\u00a7c")) agents += (agents.isEmpty() ? "" : ",")
            + "{\"id\":\"%1$s\",\"name\":\"x\",\"skin\":\"kit\",\"state\":\"idle\",\"station\":\"desk\",\"activity\":\"\"}".formatted(id);
        ForemanState state = ForemanStates.fromSnapshot(JsonParser.parseString("{\"agents\":[" + agents + "]}").getAsJsonObject());
        assertEquals(List.of("kit", "all", "user"), Redactor.redact(state, PublicPolicy.DEFAULT).agents().stream().map(PublicAgent::id).toList());
        assertNull(sayTo(state, "all\u00a7c"));
        assertNull(sayTo(state, "user\u00a7c"));
        assertEquals("user", sayTo(state, "user")); // the Foreman's own word for the owner still goes out
    }

    private static String sayTo(ForemanState state, String to) {
        return ((PublicEvent.Say) Redactor.say(state, new Protocol.AgentSay("kit", "hello", to, 1), PublicPolicy.DEFAULT)).to();
    }

    @Test void task_done_fires_only_for_a_new_done_task_with_a_published_assignee() {
        ForemanState state = ForemanStates.showcase();
        Protocol.Task review = task("t-new", Protocol.TaskStatus.REVIEW, "kit");
        Protocol.Task done = task("t-new", Protocol.TaskStatus.DONE, "kit");
        assertNotNull(Redactor.taskDone(state, review, done));
        assertNull(Redactor.taskDone(state, done, done));
        assertNull(Redactor.taskDone(state, null, task("t-x", Protocol.TaskStatus.DONE, "/srv/not-an-agent")));
        assertNull(Redactor.taskDone(state, null, review));
    }

    private static Protocol.Task task(String id, Protocol.TaskStatus status, String assignee) {
        return new Protocol.Task(id, "title", null, status, assignee, List.of(), null, null, 0, null, null, Protocol.CiStatus.UNKNOWN, null, null, null, 0, 0);
    }

    @Test void showcase_state_round_trips_through_the_codec_with_rev_left_at_zero() {
        PublicStudioState published = Redactor.redact(ForemanStates.showcase(), PublicPolicy.DEFAULT);
        assertEquals(0, published.rev());
        assertEquals(published, roundTrip(published));
        assertFalse(published.ci().isEmpty());
        assertTrue(published.foremanOnline());
    }

    private static PublicAgent agent(PublicStudioState state, String id) {
        return state.agents().stream().filter(agent -> agent.id().equals(id)).findFirst().orElseThrow();
    }

    private static ForemanState markedState() {
        return ForemanStates.fromSnapshot(JsonParser.parseString("""
            {
              "agents":[{"id":"ada","name":"Ada","skin":"ada","state":"idle","station":"desk","activity":"ZQ1 private activity"}],
              "tasks":[{"id":"t1","title":"ZQ2 private task title","status":"todo","assignee":"ada"}],
              "decisions":[{"id":"d1","agentId":"ada","kind":"question","question":"ZQ5 private decision question","status":"open","taskId":"t1"}],
              "repos":[{"id":"ZQ7-private-repo","name":"ZQ8 private repo name","path":"ZQ9-private-path","branch":"main","ci":"pass"}],
              "goal":{"id":"g","text":"ZQ3 private goal","progress":0.5,"status":"active"},
              "logs":[{"agentId":"ada","entries":[{"ts":1,"kind":"text","text":"ZQ6 private log"}]}]
            }
            """).getAsJsonObject());
    }

    private static void assertMarkersHidden(List<String> markers, String haystack) {
        for (String marker : markers) assertFalse(haystack.contains(marker), marker);
    }

    private static String markerIn(String haystack, List<String> markers) {
        List<String> found = markers.stream().filter(haystack::contains).toList();
        assertEquals(1, found.size(), found.toString());
        return found.getFirst();
    }

    private static String published(PublicStudioState state) {
        String json = PublicJson.toJson(state).toString();
        String wire = new String(stateBytes(state), StandardCharsets.UTF_8);
        return json + "\n" + wire;
    }

    private static PublicStudioState roundTrip(PublicStudioState state) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PublicStateC2S.CODEC.encode(buf, new PublicStateC2S(state));
            return PublicStateC2S.CODEC.decode(buf).state();
        } finally { buf.release(); }
    }

    private static byte[] stateBytes(PublicStudioState state) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            PublicStateC2S.CODEC.encode(buf, new PublicStateC2S(state));
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally { buf.release(); }
    }

    private static byte[] eventBytes(PublicEvent event) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            StudioEventC2S.CODEC.encode(buf, new StudioEventC2S(event));
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally { buf.release(); }
    }

    /** Free-text values taken from the fixture. Public identity (id, name, skin) is not a secret. */
    private static final class Secrets {
        final String activity;
        final String title;
        final String goal;
        final String question;
        final String log;
        final String repoId;
        final String path;
        final Set<String> activities;
        final List<String> all;

        private Secrets(String activity, String title, String goal, String question, String log, String repoId, String path, Set<String> activities, List<String> all) {
            this.activity = activity;
            this.title = title;
            this.goal = goal;
            this.question = question;
            this.log = log;
            this.repoId = repoId;
            this.path = path;
            this.activities = activities;
            this.all = all;
        }

        static Secrets of(ForemanState state) {
            List<String> activities = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            List<String> questions = new ArrayList<>();
            List<String> logs = new ArrayList<>();
            List<String> repoIds = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            List<String> all = new ArrayList<>();
            for (Protocol.Agent agent : state.agents().values()) {
                if (!agent.activity().isBlank()) activities.add(agent.activity());
                add(all, agent.title());
                add(all, agent.color());
                add(all, agent.accent());
                add(all, agent.taskId());
                add(all, agent.repoId());
                add(all, agent.worktree());
            }
            for (Protocol.Task task : state.tasks().values()) {
                if (task.title() != null && !task.title().isBlank()) titles.add(task.title());
                add(all, task.description());
                add(all, task.summary());
                add(all, task.blockedReason());
                add(all, task.repoId());
                add(all, task.branch());
                add(all, task.worktree());
            }
            for (Protocol.Decision decision : state.decisions().values()) {
                if (!decision.question().isBlank()) questions.add(decision.question());
                add(all, decision.context());
                add(all, decision.repoId());
                add(all, decision.worktree());
                add(all, decision.tool());
                if (decision.answer() != null) add(all, decision.answer().text());
                for (String option : decision.options()) add(all, option);
            }
            for (Protocol.Agent agent : state.agents().values()) for (Protocol.LogEntry entry : state.logs(agent.id())) if (!entry.text().isBlank()) logs.add(entry.text());
            for (Protocol.Repo repo : state.repos().values()) {
                repoIds.add(repo.id());
                add(all, repo.name());
                add(all, repo.branch());
                add(all, repo.head());
                if (!repo.path().isBlank()) paths.add(repo.path());
                for (Protocol.Worktree worktree : repo.worktrees()) {
                    if (worktree.path() != null && !worktree.path().isBlank()) paths.add(worktree.path());
                    add(all, worktree.branch());
                    add(all, worktree.id());
                }
            }
            all.addAll(activities);
            all.addAll(titles);
            all.addAll(questions);
            all.addAll(logs);
            all.addAll(repoIds);
            all.addAll(paths);
            if (state.goal() != null) all.add(state.goal().text());
            for (Protocol.FeedItem item : state.feed()) all.add(item.text());
            for (Protocol.MemoryEntry entry : state.memory().values()) { all.add(entry.title()); all.add(entry.body()); }
            if (state.status() != null) {
                all.add(state.status().message());
                all.add(state.status().userName());
                all.add(state.status().version());
            }
            assertFalse(activities.isEmpty());
            assertFalse(titles.isEmpty());
            assertNotNull(state.goal());
            assertFalse(state.goal().text().isBlank());
            assertFalse(questions.isEmpty());
            assertFalse(logs.isEmpty());
            assertFalse(repoIds.isEmpty());
            assertFalse(paths.isEmpty());
            return new Secrets(activities.get(0), titles.get(0), state.goal().text(), questions.get(0), logs.get(0), repoIds.get(0), paths.get(0), Set.copyOf(activities), all);
        }

        void assertHidden(ForemanState state, String haystack) {
            Set<String> allowed = new HashSet<>();
            for (String word : List.of("true", "false", "idle", "thinking", "reading", "editing", "running", "testing", "waiting_user", "blocked", "done", "error",
                "desk", "library", "terminal", "testbench", "mergestation", "meeting", "lounge", "user", "none", "planning", "active", "failed", "cancelled",
                "unknown", "pass", "fail")) allowed.add(word);
            for (Protocol.Agent agent : state.agents().values()) {
                allowed.add(agent.id());
                allowed.add(agent.name());
                allowed.add(agent.skin());
            }
            for (String secret : all) {
                if (secret == null || secret.isBlank() || allowed.contains(secret)) continue;
                assertFalse(present(haystack, secret), secret);
            }
        }

        private static void add(List<String> into, String value) {
            if (value != null && !value.isBlank()) into.add(value);
        }

        /** Match a whole JSON string, so a key such as {@code openDecisions} does not count as the word inside it. */
        private static boolean present(String haystack, String secret) {
            String encoded = new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(secret);
            return haystack.contains(encoded);
        }
    }
}
