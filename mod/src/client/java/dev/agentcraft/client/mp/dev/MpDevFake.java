package dev.agentcraft.client.mp.dev;

import com.google.gson.*;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.mp.*;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.state.*;
import java.util.UUID;

/** Local-only renderer fixture. It never publishes to the server or touches Foreman state. */
public final class MpDevFake {
    public static final StudioId FAKE = StudioId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private MpDevFake() {}
    public static void init() {
        DevBridge.register("dev.mp.fake",10000,"{state, owner?: Bob, overlay?: true} | {event} | {clear:true}",
            (req,mc)->DevBridge.onClient(mc,()->apply(req)));
        DevBridge.register("dev.mp.studios",10000,"{} - studios and multiplayer mode",
            (req,mc)->DevBridge.onClient(mc,MpDevFake::studios));
    }
    public static JsonObject apply(JsonObject request) {
        Fields f=Fields.of(request);
        int actions=(f.has("state")?1:0)+(f.has("event")?1:0)+(f.has("clear")?1:0);
        if(actions!=1) throw new DevBridge.DevException("choose state, event or clear");
        try {
            if(f.has("clear")) {
                if(!f.bool("clear")) throw new DevBridge.DevException("clear must be true");
                Studios.remove(FAKE); Studios.setOverlay(null);
                MpLog.event(MpEvents.FAKE_STUDIO,"studio",FAKE.owner(),"action","clear","agents",0);
            } else if(f.has("event")) {
                if(Studios.view(FAKE).isEmpty()) throw new DevBridge.DevException("set a fake studio before injecting an event");
                Studios.fireEvent(FAKE,PublicJson.eventFromJson(f.obj("event").json()));
            } else {
                PublicStudioState state=PublicJson.fromJson(f.obj("state").json());
                String owner=MpText.sanitize(f.optStr("owner","Bob"),16);
                boolean overlay=f.optBool("overlay",true);
                StudioView own=Studios.own();
                Studios.setOverlay(overlay?FAKE:null);
                Studios.put(new StudioView(FAKE,false,owner,true,own.layout(),state,0));
                MpLog.event(MpEvents.FAKE_STUDIO,"studio",FAKE.owner(),"action","set","agents",state.agents().size(),"rev",state.rev());
            }
        } catch(IllegalArgumentException e) { throw new DevBridge.DevException("invalid public studio fixture: "+e.getMessage()); }
        return studios();
    }
    public static JsonObject studios() {
        JsonObject out=new JsonObject(); out.addProperty("mode",MpMode.current().name());
        JsonArray array=new JsonArray();
        for(StudioView studio:Studios.all()) {
            JsonObject o=new JsonObject(); o.addProperty("studio",studio.id().owner().toString());
            o.addProperty("own",studio.own()); o.addProperty("ownerName",studio.ownerName());
            o.addProperty("online",studio.online()); o.addProperty("slot",studio.slot());
            o.addProperty("layout",studio.layout().name()); o.addProperty("anchors",studio.layout().anchors().size());
            o.add("publicState",studio.publicState()==null?JsonNull.INSTANCE:PublicJson.toJson(studio.publicState())); array.add(o);
        }
        out.add("studios",array); return out;
    }
}
