package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import dev.agentcraft.hq.HqBuilder;
import dev.agentcraft.layout.Anchors;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class AnchorsTest {
    @Test void origin_offsets_all_builder_paths_and_bounds_without_rotating() {
        var a=Anchors.builder("studio").origin(new BlockPos(128,7,-128))
            .put("direct",1,2,3,90,30).spot("desk_kit",2,66,3,45)
            .camera("door",3,70,4,12,34).cameraLookAt("look",0,70,0,1,70,1)
            .bounds(10,80,20,-10,60,-20).build();
        assertEquals(129,a.get("direct").x()); assertEquals(9,a.get("direct").y()); assertEquals(-125,a.get("direct").z());
        assertEquals(90,a.get("direct").yaw()); assertEquals(30,a.get("direct").pitch());
        assertEquals(130.5,a.get("desk_kit").x()); assertEquals(73,a.get("desk_kit").y());
        assertEquals(131,a.get("cam_door").x()); assertEquals(12,a.get("cam_door").yaw());
        assertEquals(128,a.get("cam_look").x()); assertEquals(-45,a.get("cam_look").yaw());
        assertEquals(new Anchors.Bounds(118,67,-148,138,87,-108),a.bounds());
    }
    @Test void legacy_self_and_studio_snapshots_remain_independent() {
        StudioId other=StudioId.of(UUID.randomUUID());
        var local=Anchors.builder("local").spot("desk_kit",0,66,0,0).build();
        var remote=Anchors.builder("remote").spot("desk_kit",128,66,0,0).build();
        List<String> notices=new ArrayList<>();
        Anchors.addStudioListener((id,l)->notices.add(id+":"+l.name()));
        try {
            Anchors.setSelf(StudioId.LOCAL); Anchors.publish(local); Anchors.publish(other,remote);
            assertEquals(Anchors.forStudio(StudioId.LOCAL),Anchors.current()); assertEquals(local.get("desk_kit"),Anchors.get("desk_kit"));
            Anchors.setSelf(other); assertEquals(remote,Anchors.current());
            assertThrows(UnsupportedOperationException.class,()->Anchors.all().clear());
            assertThrows(UnsupportedOperationException.class,()->remote.anchors().clear());
            Anchors.remove(other); assertEquals(Anchors.Layout.EMPTY,Anchors.current());
            assertEquals(3,notices.size());
        } finally { Anchors.setSelf(StudioId.LOCAL); Anchors.remove(other); Anchors.publish(Anchors.Layout.EMPTY); }
    }
    @Test void old_options_constructor_keeps_origin_and_local_identity() {
        var o=new HqBuilder.Options(true); assertTrue(o.force()); assertEquals(BlockPos.ZERO,o.origin()); assertEquals(StudioId.LOCAL,o.studio());
        assertEquals(new HqBuilder.Options(false),HqBuilder.Options.DEFAULT);
    }
    @Test void identity_noop_does_not_notify_and_snapshot_publish_preserves_revision() {
        Anchors.setSelf(StudioId.LOCAL); int[] calls={0}; boolean[] active={true};
        Anchors.addListener(layout->{ if(active[0]) calls[0]++; });
        try {
            Anchors.setSelf(StudioId.LOCAL); assertEquals(0,calls[0]);
            var snapshot=new Anchors.Layout("studio",7,null,Map.of()); Anchors.publish(snapshot);
            assertEquals(7,Anchors.current().revision()); assertEquals(1,calls[0]);
            Anchors.remove(StudioId.LOCAL); Anchors.setSelf(StudioId.LOCAL); assertEquals(2,calls[0]);
        } finally { active[0]=false; Anchors.publish(Anchors.Layout.EMPTY); }
    }
    @Test void public_json_layout_format_round_trips_with_and_without_bounds() {
        var builder=Anchors.builder("persisted").put("desk",128.5,66,-127.5,90,30);
        var unbounded=builder.build();
        var bounded=builder.bounds(82,60,-164,174,100,-74).build();
        for(var layout:List.of(unbounded,bounded)) {
            var snapshot=new Anchors.Layout(layout.name(),7,layout.bounds(),layout.anchors());
            assertEquals(snapshot,Anchors.fromJson(Anchors.toJson(snapshot)));
        }
    }
}
