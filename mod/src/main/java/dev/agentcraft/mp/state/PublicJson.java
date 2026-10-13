package dev.agentcraft.mp.state;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

/** Strict JSON boundary shared by dev injection and bounded wire codecs. Unknown fields fail closed. */
public final class PublicJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
        .registerTypeAdapterFactory(new WireEnums()).create();
    private PublicJson() {}
    private static final class WireEnums implements TypeAdapterFactory {
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, com.google.gson.reflect.TypeToken<T> type) {
            Class<? super T> raw=type.getRawType();
            if (!raw.isEnum() || !raw.getPackageName().equals("dev.agentcraft.mp.state")) return null;
            return new TypeAdapter<T>() {
                public void write(com.google.gson.stream.JsonWriter w,T v) throws java.io.IOException { w.value(((Enum<?>)v).name().toLowerCase(Locale.ROOT)); }
                public T read(com.google.gson.stream.JsonReader r) throws java.io.IOException {
                    String s=r.nextString();
                    for(Object v:raw.getEnumConstants()) if (((Enum<?>)v).name().toLowerCase(Locale.ROOT).equals(s)) return (T)v;
                    throw new IllegalArgumentException("invalid wire enum");
                }
            };
        }
    }
    public static JsonObject toJson(PublicStudioState s) { return GSON.toJsonTree(s).getAsJsonObject(); }
    public static JsonObject toJson(WorldIntent s) { return GSON.toJsonTree(s).getAsJsonObject(); }
    public static JsonObject toJson(PublicEvent e) {
        JsonObject o=GSON.toJsonTree(e).getAsJsonObject();
        o.addProperty("type",e instanceof PublicEvent.Say ? "say" : "task_done"); return o;
    }
    public static PublicStudioState fromJson(JsonObject o) {
        shape(o,"rev foremanOnline agents counts goal ci policy tasks");
        PublicPolicy policy=policy(obj(o,"policy"));
        List<PublicAgent> agents=list(o,"agents",16, e->agent(object(e)));
        Counts counts=counts(obj(o,"counts")); GoalSummary goal=goal(obj(o,"goal"));
        List<CiSlot> ci=list(o,"ci",8,e->ci(object(e)));
        List<PublicTask> tasks=absent(o,"tasks") ? null : list(o,"tasks",32,e->task(object(e)));
        // Reject rather than silently accepting accidental opt-in text.
        if ((!policy.activityText() && agents.stream().anyMatch(a->a.activity()!=null)) ||
            (!policy.goalText() && goal.text()!=null) || (!policy.taskTitles() && tasks!=null))
            throw new IllegalArgumentException("text without public policy");
        if (agents.stream().map(PublicAgent::id).distinct().count()!=agents.size() ||
            ci.stream().map(CiSlot::slot).distinct().count()!=ci.size()) throw new IllegalArgumentException("duplicate public identity");
        return new PublicStudioState(num(o,"rev",0,Integer.MAX_VALUE),bool(o,"foremanOnline"),agents,counts,goal,ci,policy,tasks);
    }
    public static PublicStudioState fromJson(String s) { return fromJson(parse(s,30000)); }
    public static PublicEvent eventFromJson(JsonObject o) {
        String type=str(o,"type",16);
        if (type.equals("say")) {
            shape(o,"type agentId to text length");
            return new PublicEvent.Say(str(o,"agentId",16),optional(o,"to",16),optional(o,"text",120),num(o,"length",0,Integer.MAX_VALUE));
        }
        if (type.equals("task_done")) { shape(o,"type agentId"); return new PublicEvent.TaskDone(str(o,"agentId",16)); }
        throw new IllegalArgumentException("invalid event type");
    }
    public static PublicEvent eventFromJson(String s) { return eventFromJson(parse(s,2048)); }
    public static WorldIntent intentFromJson(JsonObject o) {
        shape(o,"rev lamps podiumOpen mergeActive litMonitors");
        JsonObject lamps=obj(o,"lamps"); if(lamps.size()>64) throw new IllegalArgumentException("binding cap exceeded");
        Map<String,LampStatusWire> map=new LinkedHashMap<>();
        lamps.entrySet().forEach(e->{ String key=e.getKey(); if(!WorldIntent.isBinding(key)) throw new IllegalArgumentException("invalid binding"); if(map.put(key,en(e.getValue(),LampStatusWire.class))!=null) throw new IllegalArgumentException("duplicate binding"); });
        List<String> lit=list(o,"litMonitors",64,e->{
            String id=string(e,16);
            if(!id.equals(e.getAsString()) || id.isEmpty()) throw new IllegalArgumentException("invalid monitor");
            return id;
        }); Set<String> set=new LinkedHashSet<>(lit);
        if(set.size()!=lit.size()) throw new IllegalArgumentException("duplicate monitor");
        return new WorldIntent(num(o,"rev",0,Integer.MAX_VALUE),map,bool(o,"podiumOpen"),bool(o,"mergeActive"),set);
    }
    public static WorldIntent intentFromJson(String s) { return intentFromJson(parse(s,16384)); }
    private static JsonObject parse(String s,int cap) {
        if(s.length()>cap) throw new IllegalArgumentException("JSON cap exceeded");
        try(var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(s))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value=read(reader,0);
            if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing JSON");
            return object(value);
        } catch(java.io.IOException | JsonParseException e) { throw new IllegalArgumentException("invalid JSON"); }
    }
    private static JsonElement read(com.google.gson.stream.JsonReader r,int depth) throws java.io.IOException {
        if(depth>16) throw new IllegalArgumentException("JSON nesting cap exceeded");
        return switch(r.peek()) {
            case BEGIN_OBJECT -> {
                r.beginObject(); JsonObject o=new JsonObject();
                while(r.hasNext()) {
                    String key=r.nextName();
                    if(o.has(key)) throw new IllegalArgumentException("duplicate JSON member");
                    o.add(key,read(r,depth+1));
                }
                r.endObject(); yield o;
            }
            case BEGIN_ARRAY -> {
                r.beginArray(); JsonArray a=new JsonArray();
                while(r.hasNext()) a.add(read(r,depth+1));
                r.endArray(); yield a;
            }
            case STRING -> new JsonPrimitive(r.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(r.nextString()));
            case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
            case NULL -> { r.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("invalid JSON");
        };
    }
    private static PublicAgent agent(JsonObject o) {
        shape(o,"id name skin state station active paused awaitingUser activity");
        return new PublicAgent(str(o,"id",16),str(o,"name",16),str(o,"skin",16),en(o.get("state"),AgentStateWire.class),en(o.get("station"),StationWire.class),bool(o,"active"),bool(o,"paused"),bool(o,"awaitingUser"),optional(o,"activity",48));
    }
    private static Counts counts(JsonObject o) {
        shape(o,"todo doing review done blocked openDecisions openMerges");
        return new Counts(count(o,"todo"),count(o,"doing"),count(o,"review"),count(o,"done"),count(o,"blocked"),count(o,"openDecisions"),count(o,"openMerges"));
    }
    private static GoalSummary goal(JsonObject o) {
        shape(o,"status progress text"); JsonElement e=o.get("progress");
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("invalid progress");
        float p=e.getAsFloat(); if(!Float.isFinite(p) || p<0 || p>1) throw new IllegalArgumentException("invalid progress");
        return new GoalSummary(en(o.get("status"),GoalStatusWire.class),p,optional(o,"text",120));
    }
    private static CiSlot ci(JsonObject o) { shape(o,"slot ci"); return new CiSlot(num(o,"slot",0,7),en(o.get("ci"),CiStatusWire.class)); }
    private static PublicTask task(JsonObject o) { shape(o,"id title status assignee"); return new PublicTask(str(o,"id",48),str(o,"title",80),en(o.get("status"),TaskStatusWire.class),optional(o,"assignee",16)); }
    private static PublicPolicy policy(JsonObject o) {
        shape(o,"activityText sayText taskTitles goalText");
        return new PublicPolicy(bool(o,"activityText"),bool(o,"sayText"),bool(o,"taskTitles"),bool(o,"goalText"));
    }
    private static int count(JsonObject o,String k) { return num(o,k,0,Integer.MAX_VALUE); }
    private static void shape(JsonObject o,String keys) {
        Set<String> allowed=Set.of(keys.split(" "));
        for(String key:o.keySet()) if(!allowed.contains(key)) throw new IllegalArgumentException("unknown public field");
    }
    private static JsonObject object(JsonElement e) {
        if(e==null || !e.isJsonObject()) throw new IllegalArgumentException("expected object"); return e.getAsJsonObject();
    }
    private static JsonObject obj(JsonObject o,String k) { return object(o.get(k)); }
    private static boolean absent(JsonObject o,String k) { return !o.has(k) || o.get(k).isJsonNull(); }
    private static String string(JsonElement e,int cap) {
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("expected string");
        return MpText.sanitize(e.getAsString(),cap);
    }
    private static String str(JsonObject o,String k,int cap) { return string(o.get(k),cap); }
    private static String optional(JsonObject o,String k,int cap) { return absent(o,k) ? null : str(o,k,cap); }
    private static boolean bool(JsonObject o,String k) {
        JsonElement e=o.get(k); if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("expected boolean"); return e.getAsBoolean();
    }
    private static int num(JsonObject o,String k,int min,int max) {
        JsonElement e=o.get(k); if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("expected integer");
        try {
            int n=e.getAsBigDecimal().intValueExact(); if(n<min || n>max) throw new IllegalArgumentException("integer out of range"); return n;
        } catch(ArithmeticException e2) { throw new IllegalArgumentException("expected integer"); }
    }
    private static <E extends Enum<E>> E en(JsonElement e,Class<E> type) {
        String s=string(e,32);
        for(E v:type.getEnumConstants()) if(v.name().toLowerCase(Locale.ROOT).equals(s)) return v;
        throw new IllegalArgumentException("invalid wire enum");
    }
    private static <T> List<T> list(JsonObject o,String k,int cap,Function<JsonElement,T> decode) {
        JsonElement e=o.get(k); if(e==null || !e.isJsonArray()) throw new IllegalArgumentException("expected array");
        JsonArray a=e.getAsJsonArray(); if(a.size()>cap) throw new IllegalArgumentException("collection cap exceeded");
        List<T> list=new ArrayList<>(a.size()); for(JsonElement item:a) list.add(decode.apply(item)); return List.copyOf(list);
    }
}
