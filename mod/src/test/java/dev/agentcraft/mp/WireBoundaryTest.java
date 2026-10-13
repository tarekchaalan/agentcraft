package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.*;
import dev.agentcraft.mp.state.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WireBoundaryTest {
    private static PublicStudioState decodeState(String json) {
        var b=CodecTest.buf();
        try { b.writeUtf(json,30000); return PublicStateC2S.CODEC.decode(b).state(); }
        finally { b.release(); }
    }
    private static PublicEvent decodeEvent(String json) {
        var b=CodecTest.buf();
        try { b.writeUtf(json,2048); return StudioEventC2S.CODEC.decode(b).event(); }
        finally { b.release(); }
    }
    private static String fill(String unit,int cap) { return unit.repeat(cap/unit.length()); }
    @Test void accepted_at_cap_states_and_events_are_relay_stable() {
        StudioId id=StudioId.of(UUID.randomUUID()); int worstState=0, worstEvent=0;
        for(String unit:List.of("\u2028","\u2029","\ud800","\udc00","\"","\\","😀")) {
            var raw=PublicJson.toJson(CodecTest.state());
            raw.addProperty("foremanOnline",false);
            raw.add("policy",JsonParser.parseString("{\"activityText\":true,\"sayText\":true,\"taskTitles\":true,\"goalText\":true}"));
            JsonArray agents=new JsonArray(), tasks=new JsonArray(), ci=new JsonArray();
            for(int i=0;i<16;i++) {
                var a=raw.getAsJsonArray("agents").get(0).deepCopy().getAsJsonObject();
                // Prefix keeps identities distinct after controls/surrogates are removed.
                // Ids and skins are resource paths: only a fill that sanitizing removes may ride in them.
                String path=MpText.sanitize(unit,2).isEmpty() ? unit : "s";
                a.addProperty("id",String.format("%02d",i)+fill(path,14));
                a.addProperty("name",fill(unit,16)); a.addProperty("skin",fill(path,16));
                a.addProperty("activity",fill(unit,48)); a.addProperty("paused",i%2==0); a.addProperty("awaitingUser",i%2!=0); agents.add(a);
            }
            for(int i=0;i<32;i++) {
                JsonObject t=new JsonObject(); t.addProperty("id",fill(unit,48)); t.addProperty("title",fill(unit,80));
                t.addProperty("status","todo"); t.addProperty("assignee",fill(unit,16)); tasks.add(t);
            }
            for(int i=0;i<8;i++) { JsonObject c=new JsonObject(); c.addProperty("slot",i); c.addProperty("ci","unknown"); ci.add(c); }
            raw.add("agents",agents); raw.add("tasks",tasks); raw.add("ci",ci); raw.getAsJsonObject("goal").addProperty("text",fill(unit,120));
            String input=raw.toString();
            // JSON escapes preserve lone surrogates; raw separators exercise expansion on relay.
            input=input.replace("\\u2028","\u2028").replace("\\u2029","\u2029");
            if(unit.equals("\ud800")) input=input.replace("\ud800","\\ud800");
            if(unit.equals("\udc00")) input=input.replace("\udc00","\\udc00");
            // A fully escaped surrogate fill exceeds the incoming envelope. Keep all
            // collections/fields at their caps, with surrogate ids and task titles; use
            // ASCII in the other fields so this remains an admissible wire state.
            if(input.length()>30000) {
                for(JsonElement element:agents) {
                    var agent=element.getAsJsonObject(); agent.addProperty("name","n".repeat(16));
                    agent.addProperty("skin","s".repeat(16)); agent.addProperty("activity","a".repeat(48));
                }
                for(JsonElement element:tasks) {
                    var task=element.getAsJsonObject(); task.addProperty("id","t".repeat(48)); task.addProperty("assignee","a".repeat(16));
                }
                raw.getAsJsonObject("goal").addProperty("text","g".repeat(120));
                input=raw.toString().replace(unit,unit.equals("\ud800")?"\\ud800":"\\udc00");
                assertTrue(input.length()<=30000);
            }
            PublicStudioState state=decodeState(input);
            String encoded=PublicJson.toJson(state).toString(); worstState=Math.max(worstState,encoded.length());
            assertTrue(encoded.length()<=30000);
            assertEquals(state,CodecTest.round(StudioStateS2C.CODEC,new StudioStateS2C(id,state)).state());
            for(PublicAgent a:state.agents()) assertEquals(a.name(),MpText.sanitize(a.name(),16));
            JsonObject event=PublicJson.toJson(new PublicEvent.Say(fill(unit,16),fill(unit,16),fill(unit,120),120));
            String eventInput=event.toString().replace("\\u2028","\u2028").replace("\\u2029","\u2029");
            if(unit.equals("\ud800")) eventInput=eventInput.replace(unit,"\\ud800");
            if(unit.equals("\udc00")) eventInput=eventInput.replace(unit,"\\udc00");
            PublicEvent accepted=decodeEvent(eventInput);
            worstEvent=Math.max(worstEvent,PublicJson.toJson(accepted).toString().length());
            assertEquals(accepted,CodecTest.round(StudioEventS2C.CODEC,new StudioEventS2C(id,accepted)).event());
        }
        assertTrue(worstState>15000 && worstState<=30000,"worst encoded state chars="+worstState);
        assertTrue(worstEvent>300 && worstEvent<=2048,"worst encoded event chars="+worstEvent);
        assertEquals("ok",MpText.sanitize("§😀o\u0000\u200d\u2028\u2029\ud800\n\udc01k",30));
        assertEquals("ok",MpText.sanitize("o\ud800k\udc00",16));
    }
    @Test void strict_json_refuses_comments_duplicates_and_trailing_values() {
        String valid=PublicJson.toJson(CodecTest.state()).toString();
        for(String raw:List.of(valid+" // comment",valid+" {}",valid.replace("\"rev\":1","rev:1"),
                valid.replace("\"rev\":1","'rev':1"),valid.replace("\"activityText\":false","\"activityText\":false,\"activityText\":true"),
                "{\"agents\":"+"[".repeat(18)+"0"+"]".repeat(18)+"}")) assertThrows(RuntimeException.class,()->decodeState(raw));
        assertEquals(CodecTest.state(),decodeState(valid));
    }
    @Test void binding_grammar_is_closed_at_construction_and_decode() {
        for(String key:List.of("ci:demo-app","ci:#9","agent:","x".repeat(48),"agent:§aid","agent:\u2028id","agent:"+"x".repeat(17))) {
            assertFalse(WorldIntent.isBinding(key));
            assertEquals("invalid binding",assertThrows(IllegalArgumentException.class,()->new WorldIntent(0,Map.of(key,LampStatusWire.OFF),false,false,Set.of())).getMessage());
            var raw=PublicJson.toJson(new WorldIntent(0,Map.of(),false,false,Set.of())); raw.getAsJsonObject("lamps").addProperty(key,"off");
            var b=CodecTest.buf(); try { b.writeUtf(raw.toString()); assertThrows(RuntimeException.class,()->WorldIntentC2S.CODEC.decode(b)); } finally { b.release(); }
        }
        List<String> valid=new ArrayList<>(List.of("agent:id","agent:"+"a".repeat(16),"agent:😀","goal","goal:atrium","decisions","merge","beacon"));
        for(int i=1;i<=8;i++) valid.add("ci:#"+i);
        for(String key:valid) {
            assertTrue(WorldIntent.isBinding(key));
            WorldIntent intent=new WorldIntent(0,Map.of(key,LampStatusWire.OFF),false,false,Set.of("id"));
            assertEquals(intent,CodecTest.round(WorldIntentC2S.CODEC,new WorldIntentC2S(intent)).intent());
        }
        for(String id:List.of("","x".repeat(17),"§aid")) {
            assertThrows(IllegalArgumentException.class,()->new WorldIntent(0,Map.of(),false,false,Set.of(id)));
            var raw=PublicJson.toJson(new WorldIntent(0,Map.of(),false,false,Set.of())); raw.getAsJsonArray("litMonitors").add(id);
            assertThrows(IllegalArgumentException.class,()->PublicJson.intentFromJson(raw));
        }
    }
    @Test void hello_rates_and_combined_plot_origin_are_checked_before_handling() {
        var valid=new HelloS2C(1,StudioId.LOCAL,3,new ServerInfo(256,24,7,19));
        assertEquals(valid,CodecTest.round(HelloS2C.CODEC,valid));
        for(ServerInfo info:List.of(new ServerInfo(128,12,0,10),new ServerInfo(128,12,1001,10),new ServerInfo(128,12,4,0),new ServerInfo(128,12,4,1001))) {
            var b=CodecTest.buf(); try {
                HelloS2C.CODEC.encode(b,new HelloS2C(1,StudioId.LOCAL,Integer.MAX_VALUE,info));
                assertThrows(RuntimeException.class,()->HelloS2C.CODEC.decode(b));
            } finally { b.release(); }
        }
        // A plot the grid cannot place is not a decoder error, which would disconnect: the receiver refuses the hello.
        UUID player=UUID.randomUUID(); var far=new HelloS2C(1,StudioId.of(player),Integer.MAX_VALUE,new ServerInfo(1048576,12,4,10));
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(far.plotIndex(),far.serverInfo().plotStride()));
        HelloS2C decoded=CodecTest.round(HelloS2C.CODEC,far); assertEquals(far,decoded);
        MpMode.joined(false,"test");
        try(var capture=MpLog.capture()) {
            assertFalse(MpMode.receiveHello(decoded,false,player));
            assertEquals(List.of("event=hello_received protocol=1 mode=SINGLEPLAYER"),capture.lines());
            assertEquals(StudioId.LOCAL,Anchors.self()); assertTrue(MpMode.serverInfo().isEmpty());
        } finally { MpMode.disconnected(); }
    }
    @Test void a_hello_of_another_protocol_is_skipped_whole_and_refused_as_a_mismatch() {
        UUID player=UUID.randomUUID(); var enabled=new MpServerConfig(true,128,true,true,true,true,true,12,4,10);
        var b=CodecTest.buf();
        try {
            // A later protocol's hello: fields this build does not know, in an order it cannot parse.
            b.writeVarInt(2); b.writeUtf("a field protocol 1 does not have"); b.writeUUID(player); b.writeLong(7);
            HelloS2C hello=HelloS2C.CODEC.decode(b); assertEquals(0,b.readableBytes()); assertEquals(2,hello.protocol());
            MpMode.joined(false,"test");
            try(var capture=MpLog.capture()) {
                assertFalse(MpMode.receiveHello(hello,false,player));
                assertEquals(List.of("event=hello_received protocol=2 mode=SINGLEPLAYER"),capture.lines());
            }
            assertTrue(MpMode.inactiveMessage().contains("hello rejected")); assertEquals(StudioId.LOCAL,Anchors.self());
            b.clear(); b.writeVarInt(3);   // or shorter than this build's hello
            assertEquals(3,HelloS2C.CODEC.decode(b).protocol());
            b.clear(); b.writeVarInt(2); b.writeLong(7); b.writeUtf("x".repeat(40));
            HelloC2S reply=HelloC2S.CODEC.decode(b); assertEquals(0,b.readableBytes()); assertEquals(2,reply.protocol());
            assertFalse(MpPayloads.acceptHello(player,reply,enabled,true)); assertFalse(MpPayloads.isModEquipped(player));
            // This build's protocol is parsed exactly as before: trailing bytes stay unread, for the game to refuse.
            b.clear(); HelloC2S.CODEC.encode(b,new HelloC2S(MpProtocol.VERSION,"0.1.0")); b.writeByte(0);
            assertEquals(new HelloC2S(MpProtocol.VERSION,"0.1.0"),HelloC2S.CODEC.decode(b)); assertEquals(1,b.readableBytes());
        } finally { b.release(); MpMode.disconnected(); }
        var same=new HelloS2C(MpProtocol.VERSION,StudioId.of(player),3,new ServerInfo(256,24,7,19)); assertEquals(same,CodecTest.round(HelloS2C.CODEC,same));
        var reply=new HelloC2S(MpProtocol.VERSION,"0.1.0"); assertEquals(reply,CodecTest.round(HelloC2S.CODEC,reply));
    }
    private static org.junit.jupiter.api.function.Executable cap(String field,int cap,java.util.function.IntConsumer build) {
        return ()->{
            build.accept(cap);
            var e=assertThrows(IllegalArgumentException.class,()->build.accept(cap+1),field);
            assertTrue(e.getMessage().startsWith(field+" exceeds "+cap+" "),e.getMessage());
        };
    }
    private static PublicAgent agent(String id,String name,String skin,String activity) {
        return new PublicAgent(id,name,skin,AgentStateWire.IDLE,StationWire.DESK,true,false,false,activity);
    }
    private static List<String> ids(int n) { return java.util.stream.IntStream.range(0,n).mapToObj(i->"a"+i).toList(); }
    @Test void every_cap_is_accepted_at_the_limit_and_refused_one_over_where_the_record_is_built() {
        var s=CodecTest.state(); var all=new PublicPolicy(true,true,true,true); var task=new PublicTask("t","T",TaskStatusWire.TODO,null);
        assertAll(
            cap("agent id",16,n->agent("a".repeat(n),"n","s",null)),
            cap("agent name",16,n->agent("a","n".repeat(n),"s",null)),
            cap("agent skin",16,n->agent("a","n","s".repeat(n),null)),
            cap("agent activity",48,n->agent("a","n","s","x".repeat(n))),
            cap("task id",48,n->new PublicTask("t".repeat(n),"T",TaskStatusWire.TODO,null)),
            cap("task title",80,n->new PublicTask("t","T".repeat(n),TaskStatusWire.TODO,null)),
            cap("task assignee",16,n->new PublicTask("t","T",TaskStatusWire.TODO,"a".repeat(n))),
            cap("goal text",120,n->new GoalSummary(GoalStatusWire.ACTIVE,0,"g".repeat(n))),
            cap("agents",16,n->new PublicStudioState(1,true,Collections.nCopies(n,s.agents().getFirst()),s.counts(),s.goal(),s.ci(),s.policy(),null)),
            cap("ci",8,n->new PublicStudioState(1,true,s.agents(),s.counts(),s.goal(),Collections.nCopies(n,s.ci().getFirst()),s.policy(),null)),
            cap("tasks",32,n->new PublicStudioState(1,true,s.agents(),s.counts(),s.goal(),s.ci(),all,Collections.nCopies(n,task))),
            cap("say agentId",16,n->new PublicEvent.Say("a".repeat(n),null,null,1)),
            cap("say to",16,n->new PublicEvent.Say("a","a".repeat(n),null,1)),
            cap("say text",120,n->new PublicEvent.Say("a",null,"x".repeat(n),n)),
            cap("task_done agentId",16,n->new PublicEvent.TaskDone("a".repeat(n))),
            cap("lamps",64,n->{ Map<String,LampStatusWire> lamps=new HashMap<>(); for(String id:ids(n)) lamps.put("agent:"+id,LampStatusWire.OFF); new WorldIntent(1,lamps,false,false,Set.of()); }),
            cap("litMonitors",64,n->new WorldIntent(1,Map.of(),false,false,new HashSet<>(ids(n)))),
            cap("modVersion",32,n->new HelloC2S(1,"v".repeat(n))),
            cap("ownerName",16,n->new PresenceS2C(StudioId.LOCAL,"n".repeat(n),true)),
            cap("layout name",48,n->new LayoutS2C(StudioId.LOCAL,0,Anchors.builder("s".repeat(n)).build())),
            cap("anchor name",48,n->new LayoutS2C(StudioId.LOCAL,0,Anchors.builder("studio").spot("a".repeat(n),0,65,0,0).build())),
            cap("anchors",512,n->{ var layout=Anchors.builder("studio"); for(String id:ids(n)) layout.spot(id,0,65,0,0); new LayoutS2C(StudioId.LOCAL,0,layout.build()); }));
    }
    @Test void agent_ids_and_skins_are_resource_paths_at_decode_and_where_the_record_is_built() {
        // What a viewer's renderer does with a published skin: the game refuses "Wren" with an exception.
        assertThrows(RuntimeException.class,()->dev.agentcraft.AgentCraft.id("entity/agent/Wren"));
        for(String field:List.of("id","skin")) for(String bad:List.of("Wren","w ren","wr:en","wr\u00e9n","😀")) {
            var raw=PublicJson.toJson(CodecTest.state()); raw.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty(field,bad);
            assertEquals("agent "+field+" is not a resource path",assertThrows(IllegalArgumentException.class,()->PublicJson.fromJson(raw)).getMessage());
            assertThrows(RuntimeException.class,()->decodeState(raw.toString()));
            assertThrows(IllegalArgumentException.class,()->agent(field.equals("id") ? bad : "a","n",field.equals("skin") ? bad : "s",null));
        }
        String every="az_09-./x"; assertEquals(every,agent(every,"n",every,null).skin());
        assertEquals("entity/agent/"+every,dev.agentcraft.AgentCraft.id("entity/agent/"+every).getPath());
        // A skin is sanitized before it is checked, so what decoded before decodes to the same record.
        var raw=PublicJson.toJson(CodecTest.state()); raw.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("skin","\u00a7awr\nen");
        assertEquals("wren",PublicJson.fromJson(raw).agents().getFirst().skin());
        // Every agent of the Foreman fixtures is still publishable and still decodes.
        for(var foreman:List.of(ForemanStates.showcase(),ForemanStates.showcaseLate())) {
            List<PublicAgent> agents=foreman.agents().values().stream().map(a->agent(a.id(),a.name(),a.skin(),null)).toList();
            var s=CodecTest.state(); var state=new PublicStudioState(1,true,agents,s.counts(),s.goal(),s.ci(),s.policy(),null);
            assertEquals(6,agents.size()); assertEquals(state,decodeState(PublicJson.toJson(state).toString()));
        }
    }
    @Test void outbound_state_refuses_opted_out_text_and_overlength_fields_before_writing() {
        var s=CodecTest.state();
        assertThrows(IllegalArgumentException.class,()->new PublicStudioState(1,true,s.agents(),s.counts(),new GoalSummary(GoalStatusWire.ACTIVE,0,"private"),s.ci(),PublicPolicy.DEFAULT,null));
        assertThrows(IllegalArgumentException.class,()->new PublicAgent("a".repeat(17),"name","skin",AgentStateWire.EDITING,StationWire.DESK,true,false,false,null));
        // What a record still allows and the wire does not is refused by the encoder before a byte is written.
        var invalid=new PublicStudioState(-1,true,s.agents(),s.counts(),s.goal(),s.ci(),s.policy(),null);
        var b=CodecTest.buf(); try { assertThrows(IllegalArgumentException.class,()->PublicStateC2S.CODEC.encode(b,new PublicStateC2S(invalid))); assertEquals(0,b.writerIndex()); } finally { b.release(); }
        try(var capture=MpLog.capture()) {
            assertThrows(RuntimeException.class,()->decodeState("{\"private\":true}"));
            assertEquals(List.of("event=public_state_rejected reason=decode_failed"),capture.lines());
        }
    }
    @Test void maximal_legal_binary_payloads_intents_and_asymmetric_policy_round_trip() {
        Map<String,Anchor> anchors=new LinkedHashMap<>();
        for(int i=0;i<512;i++) { String name=String.format("%03d",i)+"a".repeat(45); anchors.put(name,new Anchor(name,i,66,-i,90,30)); }
        var layout=new LayoutS2C(StudioId.LOCAL,3,new Anchors.Layout("s".repeat(48),7,null,anchors));
        assertEquals(layout,CodecTest.round(LayoutS2C.CODEC,layout));
        var hello=new HelloC2S(1,"v".repeat(32)); assertEquals(hello,CodecTest.round(HelloC2S.CODEC,hello));
        assertEquals(hello,CodecTest.round(HelloC2S.CODEC,HelloC2S.forCurrentVersion("v".repeat(100))));
        assertEquals("v".repeat(31),HelloC2S.forCurrentVersion("v".repeat(31)+"😀").modVersion());
        var presence=new PresenceS2C(StudioId.LOCAL,"n".repeat(16),false); assertEquals(presence,CodecTest.round(PresenceS2C.CODEC,presence));
        Map<String,LampStatusWire> lamps=new LinkedHashMap<>(); Set<String> lit=new LinkedHashSet<>();
        for(int i=0;i<64;i++) { String id=String.format("%02d",i)+"a".repeat(14); lamps.put("agent:"+id,LampStatusWire.OFF); lit.add(id); }
        var intent=new WorldIntentC2S(new WorldIntent(3,lamps,false,true,lit)); assertEquals(intent,CodecTest.round(WorldIntentC2S.CODEC,intent));
        for(String flag:List.of("activityText","sayText","taskTitles","goalText")) {
            var raw=PublicJson.toJson(CodecTest.state()); raw.getAsJsonObject("policy").addProperty(flag,true);
            if(flag.equals("activityText")) raw.getAsJsonArray("agents").get(0).getAsJsonObject().addProperty("activity","work");
            if(flag.equals("goalText")) raw.getAsJsonObject("goal").addProperty("text","goal");
            if(flag.equals("taskTitles")) raw.add("tasks",new JsonArray());
            var state=PublicJson.fromJson(raw);
            assertEquals(raw,PublicJson.toJson(CodecTest.round(PublicStateC2S.CODEC,new PublicStateC2S(state)).state()));
        }
    }
}
