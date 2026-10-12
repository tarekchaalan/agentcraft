package dev.agentcraft.gametest.mp.relay;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.relay.StudioRelay;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.StationWire;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

public final class RelayRefusalGameTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static PlotDirectory fakeDir(UUID owner) {
        return new PlotDirectory() {
            public Optional<Plot> plotOf(StudioId id) {
                return id.owner().equals(owner)
                    ? Optional.of(new Plot(1, id, BlockPos.ZERO))
                    : Optional.empty();
            }
            public Optional<Plot> plotAt(BlockPos pos) { return Optional.empty(); }
            public Collection<Plot> all() {
                return List.of(new Plot(1, StudioId.of(owner), BlockPos.ZERO));
            }
        };
    }

    private static PublicStudioState minimalState() {
        return new PublicStudioState(1, true,
            List.of(new PublicAgent("alex", "Alex", "default",
                AgentStateWire.IDLE, StationWire.DESK, true, false, false, null)),
            new Counts(0, 0, 0, 0, 0, 0, 0),
            new GoalSummary(GoalStatusWire.NONE, 0f, null),
            List.of(),
            new PublicPolicy(false, false, false, false),
            null);
    }

    @GameTest
    public void noPlotRefusesStateAndStateIsAttributedToSender(GameTestHelper helper) {
        var previousDir = Plots.directory();
        var previousCfg = MpServerConfig.current();
        try {
            Plots.install(fakeDir(OWNER));
            MpServerConfig.install(new MpServerConfig(true, 128, true, true,
                true, true, true, 12, 4, 10));
            StudioRelay.reset();

            // Explicit timestamp 0: deterministic, no refill.
            var result = StudioRelay.acceptState(OWNER, minimalState(),
                Plots.directory(), MpServerConfig.current(), true, 0);
            helper.assertValueEqual(StudioRelay.StateResult.ACCEPTED, result,
                "owner should be accepted");

            helper.assertTrue(
                StudioRelay.stored(StudioId.of(OWNER)) != null,
                "state stored under owner UUID");
            helper.assertTrue(
                StudioRelay.stored(StudioId.LOCAL) == null,
                "state never stored under LOCAL");

            try (var capture = MpLog.capture()) {
                var refused = StudioRelay.acceptState(STRANGER, minimalState(),
                    Plots.directory(), MpServerConfig.current(), true, 0);
                helper.assertValueEqual(StudioRelay.StateResult.NO_PLOT, refused,
                    "stranger should be refused no_plot");
                helper.assertTrue(
                    capture.lines().stream().anyMatch(
                        l -> l.startsWith("event=" + MpEvents.PUBLIC_STATE_REJECTED)
                            && l.contains("reason=" + MpReasons.NO_PLOT)),
                    "no_plot telemetry captured");
            }

            helper.succeed();
        } finally {
            StudioRelay.reset();
            MpServerConfig.install(previousCfg);
            Plots.install(previousDir);
        }
    }

    @GameTest
    public void rateLimitedRefusedWithTelemetry(GameTestHelper helper) {
        var previousDir = Plots.directory();
        var previousCfg = MpServerConfig.current();
        try {
            Plots.install(fakeDir(OWNER));
            MpServerConfig.install(new MpServerConfig(true, 128, true, true,
                true, true, true, 12, 1, 10));
            StudioRelay.reset();

            // Explicit timestamp 0 for every call: no refill, deterministic.
            try (var capture = MpLog.capture()) {
                helper.assertValueEqual(StudioRelay.StateResult.ACCEPTED,
                    StudioRelay.acceptState(OWNER, minimalState(),
                        Plots.directory(), MpServerConfig.current(), true, 0),
                    "first state accepted");
                helper.assertValueEqual(StudioRelay.StateResult.UNCHANGED,
                    StudioRelay.acceptState(OWNER, minimalState(),
                        Plots.directory(), MpServerConfig.current(), true, 0),
                    "second state accepted (unchanged)");
                var refused = StudioRelay.acceptState(OWNER, minimalState(),
                    Plots.directory(), MpServerConfig.current(), true, 0);

                helper.assertValueEqual(StudioRelay.StateResult.RATE_LIMITED,
                    refused, "third state should be rate limited");
                helper.assertTrue(
                    capture.lines().stream().anyMatch(
                        l -> l.startsWith("event=" + MpEvents.PUBLIC_STATE_REJECTED)
                            && l.contains("reason=" + MpReasons.RATE_LIMITED)),
                    "rate_limited telemetry captured");
            }

            helper.succeed();
        } finally {
            StudioRelay.reset();
            MpServerConfig.install(previousCfg);
            Plots.install(previousDir);
        }
    }
}
