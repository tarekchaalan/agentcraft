package dev.agentcraft.client.agents;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.StationWire;
import dev.agentcraft.mp.state.PublicEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StudioAgentsTest {
	@Test
	void studio_entity_ids_stay_inside_their_1000_id_windows_and_reuse_released_ids() {
		StudioAgents.IdPool own = new StudioAgents.IdPool(-10_000);
		StudioAgents.IdPool visitor = new StudioAgents.IdPool(-11_000);
		List<Integer> ownIds = new ArrayList<>();
		List<Integer> visitorIds = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			ownIds.add(own.allocate());
			visitorIds.add(visitor.allocate());
		}

		assertEquals(-10_000, ownIds.getFirst());
		assertEquals(-10_999, ownIds.getLast());
		assertEquals(-11_000, visitorIds.getFirst());
		assertEquals(-11_999, visitorIds.getLast());
		assertEquals(0, own.allocate(), "slot 0 cannot consume the next studio's first id");
		assertEquals(0, visitor.allocate(), "slot 1 cannot consume another studio's id");
		assertEquals(1000, new HashSet<>(ownIds).size());
		assertTrue(java.util.Collections.disjoint(ownIds, visitorIds));

		for (int id : ownIds) own.release(id);
		for (int id : visitorIds) visitor.release(id);
		List<Integer> reusedOwn = new ArrayList<>();
		List<Integer> reusedVisitor = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			reusedOwn.add(own.allocate());
			reusedVisitor.add(visitor.allocate());
		}
		assertEquals(new HashSet<>(ownIds), new HashSet<>(reusedOwn));
		assertEquals(new HashSet<>(visitorIds), new HashSet<>(reusedVisitor));
		assertTrue(java.util.Collections.disjoint(reusedOwn, reusedVisitor));
	}

	@Test
	void plate_identity_is_studio_qualified_without_changing_the_cast_id() {
		StudioView ownView = new StudioView(StudioId.LOCAL, true, "", true, Anchors.Layout.EMPTY, null, 0);
		StudioId visitorId = StudioId.of(UUID.randomUUID());
		StudioView visitorView = new StudioView(visitorId, false, "Owner", true, Anchors.Layout.EMPTY, null, 1);
		StudioAgents own = new StudioAgents(ownView, -10_000);
		StudioAgents visitor = new StudioAgents(visitorView, -11_000);
		AgentView ownKit = new AgentView("kit");
		AgentView visitorKit = new AgentView("kit");
		ownKit.attach(own, false, "");
		visitorKit.attach(visitor, true, "Owner");

		assertEquals("kit", ownKit.id);
		assertEquals("kit", ownKit.plateKey);
		assertEquals("kit", visitorKit.id);
		assertNotEquals(ownKit.plateKey, visitorKit.plateKey);
		assertTrue(visitorKit.plateKey.endsWith("/kit"));
	}

	@Test
	void remote_waiting_targets_use_the_studios_podium_then_its_user_slots() {
		Anchors.Layout layout = Anchors.builder("waiting")
			.put("podium_user", 8.5, 66, 15.5, 90, 0)
			.put("user", 5.5, 66, 15.5, 90, 0)
			.put("user_2", 5.5, 66, 17.5, 90, 0)
			.put("user_3", 5.5, 66, 19.5, 90, 0)
			.build();

		assertEquals("podium_user", StudioAgents.waitingAnchor(layout, 0).name());
		assertEquals("user", StudioAgents.waitingAnchor(layout, 1).name());
		assertEquals("user_2", StudioAgents.waitingAnchor(layout, 2).name());
		assertEquals("user_3", StudioAgents.waitingAnchor(layout, 3).name());
		Anchors.Layout withoutPodium = Anchors.builder("waiting_without_podium")
			.put("user", 5.5, 66, 15.5, 90, 0)
			.put("user_2", 5.5, 66, 17.5, 90, 0)
			.build();
		assertEquals("user", StudioAgents.waitingAnchor(withoutPodium, 0).name());
		assertEquals("user_2", StudioAgents.waitingAnchor(withoutPodium, 1).name());

		Vec3 owner = new Vec3(4.5, 66, 8.5);
		Vec3 agent = new Vec3(6.5, 66, 8.5);
		Vec3 ownerApproach = StudioAgents.ownerApproach(owner, agent, 0, 1);
		assertEquals(StudioAgents.USER_DISTANCE, Math.sqrt(ownerApproach.distanceToSqr(owner)), 1e-9);
		assertEquals(owner.y, ownerApproach.y);
	}

	@Test
	void user_spot_name_is_local_or_remote_without_changing_the_base_name() {
		assertEquals("user@player", StudioAgents.userSpotName(false));
		assertEquals("user@owner", StudioAgents.userSpotName(true));
	}

	@Test
	void empty_own_layout_uses_spawn_fallback_only_in_singleplayer() {
		Anchors.Layout empty = Anchors.Layout.EMPTY;
		Anchors.Layout published = Anchors.builder("published").spot("spawn", 0, 66, 0, 0).build();

		assertTrue(StudioAgents.shouldRunOwnAgents(MpMode.SINGLEPLAYER, empty));
		assertTrue(StudioAgents.useSpawnFallback(MpMode.SINGLEPLAYER, empty));
		assertFalse(StudioAgents.shouldRunOwnAgents(MpMode.MULTIPLAYER, empty));
		assertFalse(StudioAgents.useSpawnFallback(MpMode.MULTIPLAYER, empty));
		assertTrue(StudioAgents.shouldRunOwnAgents(MpMode.SINGLEPLAYER, published));
		assertTrue(StudioAgents.shouldRunOwnAgents(MpMode.MULTIPLAYER, published));
		assertFalse(StudioAgents.useSpawnFallback(MpMode.SINGLEPLAYER, published));
		assertFalse(StudioAgents.useSpawnFallback(MpMode.MULTIPLAYER, published));
	}

	@Test
	void remote_agent_view_uses_only_public_activity_and_state() {
		AgentView view = new AgentView("kit");
		view.remote = true;
		PublicAgent publicAgent = new PublicAgent("kit", "Kit", "kit", AgentStateWire.EDITING,
			StationWire.DESK, true, false, false, null);
		view.updatePublic(publicAgent, false);

		assertEquals("editing", view.activityLine());
		assertNull(view.awaitingDecision);
		assertFalse(view.awaitingUser);

		view.updatePublic(new PublicAgent("kit", "Kit", "kit", AgentStateWire.EDITING, StationWire.DESK,
			true, false, true, "public activity"), false);
		assertEquals("public activity", view.activityLine());
		assertTrue(view.awaitingUser);
		view.updatePublic(publicAgent, true);
		assertEquals("Foreman offline", view.activityLine());
	}

	@Test
	void needs_you_marker_is_never_shown_for_another_studios_agent() {
		StudioView visitorView = new StudioView(StudioId.of(UUID.randomUUID()), false, "Owner", true, Anchors.Layout.EMPTY, null, 1);
		AgentView visitor = new AgentView("tove");
		visitor.attach(new StudioAgents(visitorView, -11_000), true, "Owner");
		visitor.updatePublic(new PublicAgent("tove", "Tove", "tove", AgentStateWire.WAITING_USER, StationWire.USER,
			true, false, true, null), false);

		assertFalse(visitor.needsYou(), "an agent waiting for its own owner shows no marker to a visitor");
		assertTrue(visitor.awaitingUser);
		assertEquals("waiting", visitor.family);
		assertEquals("waiting user", visitor.activityLine());

		AgentView own = new AgentView("tove");
		own.state = Protocol.AgentState.WAITING_USER;
		assertTrue(own.needsYou());
		own.state = Protocol.AgentState.EDITING;
		assertFalse(own.needsYou());
		own.awaitingUser = true;
		assertTrue(own.needsYou());
	}

	@Test
	void a_remote_agents_skin_is_resolved_again_only_when_its_published_name_changes() {
		AgentView view = new AgentView("wren");
		view.skinName = "wren";

		assertFalse(view.skinChanged("wren"));
		assertTrue(view.skinChanged("kit"));
		assertEquals("kit", view.skinName);
		assertFalse(view.skinChanged("kit"));
		assertTrue(view.skinChanged(null));
		assertFalse(view.skinChanged(null));
	}

	@Test
	void studio_attach_and_detach_telemetry_is_captured_without_public_text() {
		UUID owner = UUID.randomUUID();
		UUID player = UUID.randomUUID();
		StudioView view = new StudioView(StudioId.of(owner), false, "Visitor Owner", true, Anchors.Layout.EMPTY, null, 1);
		try (MpLog.Capture capture = MpLog.capture()) {
			StudioAgents.logAttached(view, 6, 3, 17, player);
			StudioAgents.logDetached(view, 6, 3, 17, player);
			List<String> lines = capture.lines();
			assertEquals(2, lines.size());
			assertTrue(lines.get(0).startsWith("event=" + MpEvents.AGENTS_STUDIO_ATTACHED + " "));
			assertTrue(lines.get(1).startsWith("event=" + MpEvents.AGENTS_STUDIO_DETACHED + " "));
			for (String line : lines) {
				assertTrue(line.contains("player=" + player));
				assertTrue(line.contains("studio=" + owner));
				assertTrue(line.contains("plot=3"));
				assertTrue(line.contains("rev=17"));
				assertTrue(line.contains("slot=1"));
				assertTrue(line.contains("agents=6"));
				assertFalse(line.contains("Visitor Owner"));
			}
		}
	}

	@Test
	void remote_speech_uses_ellipsis_owner_label_and_public_length_timing() {
		SpeechBubble remote = new SpeechBubble();
		SpeechBubble own = new SpeechBubble();
		remote.showRemote(new PublicEvent.Say("kit", "user", null, 40), "Bob", java.util.Map.of("kit", "Kit"), 100);
		own.show(new Protocol.AgentSay("kit", "x".repeat(40), "user", 0), 100);

		assertEquals("…", remote.current().text());
		assertEquals("@Bob", SpeechBubble.prefix(remote.current()));
		assertEquals(own.durationTicks(), remote.durationTicks());
	}
}
