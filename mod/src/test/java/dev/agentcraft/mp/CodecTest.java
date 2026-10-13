package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.*;
import dev.agentcraft.mp.state.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

public class CodecTest {
    public static PublicStudioState state() {
        return new PublicStudioState(1,true,List.of(new PublicAgent("kit","Kit","kit",AgentStateWire.EDITING,StationWire.DESK,true,false,false,null)),new Counts(1,2,3,4,5,6,7),new GoalSummary(GoalStatusWire.ACTIVE,.5f,null),List.of(new CiSlot(0,CiStatusWire.PASS)),PublicPolicy.DEFAULT,null);
    }
    static RegistryFriendlyByteBuf buf() { return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY); }
    static <T> T round(StreamCodec<RegistryFriendlyByteBuf,T> c,T value) {
        var b=buf(); try { c.encode(b,value); T decoded=c.decode(b); assertEquals(0,b.readableBytes()); return decoded; } finally { b.release(); }
    }
    @Test void every_payload_round_trips() {
        StudioId id=StudioId.of(UUID.randomUUID());
        var layout=Anchors.builder("studio").bounds(-46,60,-36,46,100,54).spot("desk_kit",1,66,2,90).build();
        assertRound(HelloS2C.CODEC,new HelloS2C(1,id,-1,new ServerInfo(128,12,4,10)));
        assertRound(HelloC2S.CODEC,new HelloC2S(1,"0.1.0"));
        assertRound(LayoutS2C.CODEC,new LayoutS2C(id,0,layout));
        assertRound(LayoutRemoveS2C.CODEC,new LayoutRemoveS2C(id));
        assertRound(PublicStateC2S.CODEC,new PublicStateC2S(state()));
        assertRound(StudioStateS2C.CODEC,new StudioStateS2C(id,state()));
        for(PublicEvent e:List.of(new PublicEvent.Say("kit","user",null,500),new PublicEvent.TaskDone("kit"))) {
            assertRound(StudioEventC2S.CODEC,new StudioEventC2S(e)); assertRound(StudioEventS2C.CODEC,new StudioEventS2C(id,e));
        }
        assertRound(PresenceS2C.CODEC,new PresenceS2C(id,"Bob",true));
        assertRound(WorldIntentC2S.CODEC,new WorldIntentC2S(new WorldIntent(1,Map.of("agent:kit",LampStatusWire.WORKING),true,false,Set.of("kit"))));
    }
    private static <T> void assertRound(StreamCodec<RegistryFriendlyByteBuf,T> c,T v) { assertEquals(v,round(c,v)); }
    private static void badState(Consumer<JsonObject> mutate) { badState(null,mutate); }
    private static void badState(String reason,Consumer<JsonObject> mutate) {
        JsonObject o=PublicJson.toJson(state()); mutate.accept(o);
        var b=buf(); try { b.writeUtf(o.toString()); var error=assertThrows(RuntimeException.class,()->PublicStateC2S.CODEC.decode(b)); if(reason!=null) assertEquals(reason,error.getCause().getMessage()); } finally { b.release(); }
    }
    @Test void every_public_state_collection_and_string_cap_is_checked_at_decode() {
        for(String key:List.of("agents","ci","tasks")) badState("collection cap exceeded",o->{
            o.getAsJsonObject("policy").addProperty("taskTitles",true);
            JsonArray a=new JsonArray(); int cap=key.equals("agents")?16:key.equals("ci")?8:32;
            for(int i=0;i<=cap;i++) {
                var item=(key.equals("agents")?o.getAsJsonArray("agents").get(0):key.equals("ci")?o.getAsJsonArray("ci").get(0):JsonParser.parseString("{\"id\":\"t\",\"title\":\"Task\",\"status\":\"todo\"}")).deepCopy().getAsJsonObject();
                if(key.equals("ci")) item.addProperty("slot",i); else item.addProperty("id","a"+i);
                a.add(item);
            }
            o.add(key,a);
        });
        for(String key:List.of("id","name","skin","activity")) badState(o->{
            o.getAsJsonObject("policy").addProperty("activityText",true);
            o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty(key,"x".repeat(key.equals("activity")?49:17));
        });
        badState(o->{ o.getAsJsonObject("policy").addProperty("goalText",true); o.getAsJsonObject("goal").addProperty("text","x".repeat(121)); });
        for(String key:List.of("id","title","assignee")) badState(o->{
            o.getAsJsonObject("policy").addProperty("taskTitles",true);
            var task=JsonParser.parseString("{\"id\":\"t\",\"title\":\"Task\",\"status\":\"todo\"}").getAsJsonObject();
            task.addProperty(key,"x".repeat(key.equals("title")?81:key.equals("id")?49:17)); JsonArray tasks=new JsonArray(); tasks.add(task); o.add("tasks",tasks);
        });
    }
    @Test void layout_and_hello_presence_event_and_intent_caps_are_checked_at_decode() {
        // An oversized record cannot be built, so each one-over wire form is written by hand.
        refused(HelloC2S.CODEC,b->{ b.writeVarInt(1); b.writeUtf("x".repeat(33)); });
        refused(PresenceS2C.CODEC,b->{ b.writeUUID(StudioId.LOCAL.owner()); b.writeUtf("x".repeat(17)); b.writeBoolean(true); });
        var atCaps=buf(); try { layout(atCaps,"x".repeat(48),512,"a".repeat(45)); assertEquals(512,LayoutS2C.CODEC.decode(atCaps).layout().anchors().size()); } finally { atCaps.release(); }
        refused(LayoutS2C.CODEC,b->layout(b,"studio",1,"x".repeat(49)));
        refused(LayoutS2C.CODEC,b->layout(b,"studio",513,"a"));
        refused(LayoutS2C.CODEC,b->layout(b,"x".repeat(49),0,"a"));
        for(var over:Map.of("agentId",17,"to",17,"text",121).entrySet()) refused(StudioEventC2S.CODEC,b->{
            var o=PublicJson.toJson(new PublicEvent.Say("kit","user","hi",2)); o.addProperty(over.getKey(),"x".repeat(over.getValue())); b.writeUtf(o.toString());
        });
        refused(StudioEventC2S.CODEC,b->{ var o=PublicJson.toJson(new PublicEvent.TaskDone("kit")); o.addProperty("agentId","x".repeat(17)); b.writeUtf(o.toString()); });
        for(boolean binding:List.of(true,false)) refused(WorldIntentC2S.CODEC,b->{
            var o=PublicJson.toJson(new WorldIntent(1,Map.of(),false,false,Set.of()));
            for(int i=0;i<65;i++) { if(binding) o.getAsJsonObject("lamps").addProperty("agent:b"+i,"off"); else o.getAsJsonArray("litMonitors").add("a"+i); }
            b.writeUtf(o.toString());
        });
        for(boolean binding:List.of(true,false)) {
            var raw=PublicJson.toJson(new WorldIntent(1,Map.of(),false,false,Set.of()));
            if(binding) raw.getAsJsonObject("lamps").addProperty("x".repeat(49),"off");
            else raw.getAsJsonArray("litMonitors").add("x".repeat(17));
            var b=buf(); try { b.writeUtf(raw.toString()); assertThrows(RuntimeException.class,()->WorldIntentC2S.CODEC.decode(b)); } finally { b.release(); }
        }
    }
    private static void layout(RegistryFriendlyByteBuf b,String name,int anchors,String key) {
        b.writeUUID(StudioId.LOCAL.owner()); b.writeVarInt(0); b.writeUtf(name); b.writeVarLong(0); b.writeBoolean(false); b.writeVarInt(anchors);
        for(int i=0;i<anchors;i++) { b.writeUtf(key+String.format("%03d",i)); b.writeDouble(0); b.writeDouble(65); b.writeDouble(0); b.writeFloat(0); b.writeFloat(0); }
    }
    private static <T> void refused(StreamCodec<RegistryFriendlyByteBuf,T> c,Consumer<RegistryFriendlyByteBuf> wire) {
        var b=buf(); try { wire.accept(b); assertThrows(RuntimeException.class,()->c.decode(b)); } finally { b.release(); }
    }
    private static <T> void assertDecodeFails(StreamCodec<RegistryFriendlyByteBuf,T> c,T v) {
        var b=buf(); try { c.encode(b,v); assertThrows(RuntimeException.class,()->c.decode(b)); } finally { b.release(); }
    }
    @Test void strings_are_sanitized_and_opt_in_fields_fail_closed() {
        assertEquals("Kit",round(HelloC2S.CODEC,new HelloC2S(1,"§aK\nit\u0000")).modVersion());
        var o=PublicJson.toJson(state()); o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("name","§cK\rit");
        assertEquals("Kit",PublicJson.fromJson(o).agents().getFirst().name());
        badState(j->j.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("activity","secret"));
        badState(j->j.getAsJsonObject("goal").addProperty("text","secret"));
        badState(j->j.add("tasks",new JsonArray()));
        assertEquals(new PublicEvent.Say("kit","user","Hi",2),PublicJson.eventFromJson(PublicJson.toJson(new PublicEvent.Say("kit","user","§aH\ni",2))));
        assertEquals(new PublicPolicy(false,false,false,false),PublicPolicy.DEFAULT);
    }
    @Test void malformed_values_never_reach_handlers() {
        badState(o->o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("state","invalid"));
        badState(o->o.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("station","invalid"));
        badState(o->o.getAsJsonObject("goal").addProperty("status","invalid"));
        badState(o->o.getAsJsonArray("ci").get(0).getAsJsonObject().addProperty("ci","invalid"));
        badState(o->o.getAsJsonObject("goal").addProperty("progress",Float.POSITIVE_INFINITY));
        badState(o->o.getAsJsonObject("counts").addProperty("todo",-1));
        badState(o->o.addProperty("foremanOnline","true"));
        badState(o->o.addProperty("rev",1.5));
        badState(o->o.addProperty("privateData","secret"));
        assertDecodeFails(LayoutS2C.CODEC,new LayoutS2C(StudioId.LOCAL,0,Anchors.builder("studio").put("a",Double.NaN,0,0,0,0).build()));
        var b=buf(); try { b.writeVarInt(1); b.writeUtf("{broken"); b.readerIndex(1); assertThrows(RuntimeException.class,()->PublicStateC2S.CODEC.decode(b)); } finally { b.release(); }
    }
    @Test void opt_in_state_accepts_exact_caps_and_preserves_the_public_allowlist() {
        List<PublicAgent> agents=new ArrayList<>();
        List<CiSlot> ci=new ArrayList<>(); List<PublicTask> tasks=new ArrayList<>();
        for(int i=0;i<16;i++) agents.add(new PublicAgent("a"+i,"N".repeat(16),"s".repeat(16),AgentStateWire.THINKING,StationWire.LIBRARY,true,false,false,"a".repeat(48)));
        for(int i=0;i<8;i++) ci.add(new CiSlot(i,CiStatusWire.UNKNOWN));
        for(int i=0;i<32;i++) tasks.add(new PublicTask("t"+i,"T".repeat(80),TaskStatusWire.TODO,"a0"));
        PublicStudioState s=new PublicStudioState(2,true,agents,new Counts(32,0,0,0,0,0,0),new GoalSummary(GoalStatusWire.PLANNING,0,"G".repeat(120)),ci,new PublicPolicy(true,true,true,true),tasks);
        assertRound(PublicStateC2S.CODEC,new PublicStateC2S(s));
    }
    @Test void public_record_shape_is_the_frozen_privacy_allowlist_and_c2s_has_no_studio_claim() {
        Map<Class<?>,String> shapes=Map.of(
            PublicStudioState.class,"rev foremanOnline agents counts goal ci policy tasks",
            PublicAgent.class,"id name skin state station active paused awaitingUser activity",
            Counts.class,"todo doing review done blocked openDecisions openMerges",
            GoalSummary.class,"status progress text",CiSlot.class,"slot ci",
            PublicTask.class,"id title status assignee",PublicPolicy.class,"activityText sayText taskTitles goalText",
            PublicEvent.Say.class,"agentId to text length",PublicEvent.TaskDone.class,"agentId",
            WorldIntent.class,"rev lamps podiumOpen mergeActive litMonitors");
        shapes.forEach((type,fields)->assertEquals(List.of(fields.split(" ")),Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList(),type.getSimpleName()));
        for(Class<?> type:List.of(HelloC2S.class,PublicStateC2S.class,StudioEventC2S.class,WorldIntentC2S.class))
            assertFalse(Arrays.stream(type.getRecordComponents()).anyMatch(c->c.getType()==StudioId.class),type.getSimpleName());
    }

}
