package dev.agentcraft.client.mp;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.state.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Client-thread registry; listeners run on the same thread as the mutation. */
public final class Studios {
    private static final Plot LOCAL_PLOT=new Plot(0,StudioId.LOCAL,BlockPos.ZERO);
    private static final Map<StudioId,StudioView> VIEWS=new LinkedHashMap<>();
    private static final Map<StudioId,Plot> PLOTS=new LinkedHashMap<>();
    private static final Map<StudioId,Integer> SLOTS=new LinkedHashMap<>();
    private static final List<BiConsumer<StudioId,StudioView>> LISTENERS=new ArrayList<>();
    private static final List<BiConsumer<StudioId,PublicEvent>> EVENT_LISTENERS=new ArrayList<>();
    private static StudioId ownId=StudioId.LOCAL;
    private static @Nullable StudioId overlay;
    private static int nextSlot=1;
    private static Plot[] plotSnapshot=new Plot[0];
    private static StudioView[] viewSnapshot=new StudioView[0];
    private static StudioView cachedOwn;
    private static Optional<StudioView> ownView=Optional.empty();
    private static final Map<StudioId,Optional<StudioView>> VIEW_OPTIONS=new HashMap<>();
    private Studios() {}
    public static void init() {
        Anchors.addStudioListener((id,layout)->anchorsChanged(Minecraft.getInstance(),id,layout));
    }
    /** Resolve the latest snapshot on the client thread; a queued removal must not create a view. */
    public static void anchorsChanged(java.util.concurrent.Executor client,StudioId id,Layout layout) {
        client.execute(()-> {
            if(Anchors.all().containsKey(id)) updateLayout(id,Anchors.forStudio(id));
            else remove(id);
        });
    }
    public static StudioView own() {
        StudioView old=VIEWS.get(ownId);
        Layout layout=Anchors.forStudio(ownId);
        if(cachedOwn==null || !cachedOwn.id().equals(ownId) || cachedOwn.layout()!=layout) {
            cachedOwn=new StudioView(ownId,true,old==null?"":old.ownerName(),old==null || old.online(),layout,old==null?null:old.publicState(),0);
            ownView=Optional.of(cachedOwn);
        }
        return cachedOwn;
    }
    public static Optional<StudioView> view(StudioId id) { if(id.equals(ownId)) { own(); return ownView; } return VIEW_OPTIONS.getOrDefault(id,Optional.empty()); }
    public static Optional<Plot> plot(StudioId id) { return Optional.ofNullable(PLOTS.get(id)); }
    public static Collection<StudioView> all() {
        List<StudioView> result=new ArrayList<>(); result.add(own());
        VIEWS.values().stream().filter(v->!v.id().equals(ownId)).forEach(result::add); return List.copyOf(result);
    }
    public static Optional<StudioView> at(BlockPos pos) {
        if(overlay!=null && in(own(),pos)) return view(overlay);
        // Plot boundaries win; layout bounds are a fallback while the plot is not yet known.
        for(Plot p:plotSnapshot) if(p.contains(pos)) { var v=view(p.owner()); if(v.isPresent()) return v; }
        if(Plots.directory()==Plots.SINGLEPLAYER && ownId.equals(StudioId.LOCAL) && LOCAL_PLOT.contains(pos)) return view(ownId);
        Optional<Plot> plot=Plots.directory().plotAt(pos);
        if(plot.isPresent()) { Optional<StudioView> v=view(plot.get().owner()); if(v.isPresent()) return v; }
        if(!PLOTS.containsKey(ownId) && bounds(own().layout(),pos)) return view(ownId);
        for(StudioView v:viewSnapshot) if(!v.id().equals(ownId) && !PLOTS.containsKey(v.id()) && bounds(v.layout(),pos)) return view(v.id());
        return Optional.empty();
    }
    private static boolean in(StudioView v,BlockPos p) {
        Plot plot=PLOTS.get(v.id());
        if(plot==null) plot=Plots.directory()==Plots.SINGLEPLAYER?(v.id().equals(StudioId.LOCAL)?LOCAL_PLOT:null):Plots.directory().plotOf(v.id()).orElse(null);
        return plot==null ? bounds(v.layout(),p) : plot.contains(p);
    }
    private static boolean bounds(Layout l,BlockPos p) { return l.bounds()!=null && l.bounds().contains(p.getX(),p.getY(),p.getZ()); }
    public static int entityIdBase(StudioId id) {
        int slot=id.equals(ownId)?0:SLOTS.computeIfAbsent(id,k->nextSlot++);
        return Math.subtractExact(-10000,Math.multiplyExact(1000,slot));
    }
    public static void addListener(BiConsumer<StudioId,StudioView> listener) { LISTENERS.add(listener); }
    public static void addEventListener(BiConsumer<StudioId,PublicEvent> listener) { EVENT_LISTENERS.add(listener); }
    public static void fireEvent(StudioId id,PublicEvent event) { EVENT_LISTENERS.forEach(l->l.accept(id,event)); }
    public static void setOwn(StudioId id) {
        ownId=id; SLOTS.put(id,0); Anchors.setSelf(id);
    }
    public static void setPlot(StudioId id,int index,int stride) {
        if(index<0) PLOTS.remove(id); else PLOTS.put(id,new Plot(index,id,PlotGrid.originOf(index,stride)));
        plotSnapshot=PLOTS.values().toArray(Plot[]::new);
    }
    public static void put(StudioView view) {
        boolean own=view.id().equals(ownId); int slot=own?0:slot(view.id());
        StudioView next=new StudioView(view.id(),own,MpText.sanitize(view.ownerName(),16),view.online(),view.layout(),view.publicState(),slot);
        VIEWS.put(view.id(),next); viewSnapshot=VIEWS.values().toArray(StudioView[]::new); VIEW_OPTIONS.put(view.id(),Optional.of(next)); if(own) cachedOwn=null; LISTENERS.forEach(l->l.accept(next.id(),next));
    }
    private static int slot(StudioId id) { if(id.equals(ownId)) return 0; entityIdBase(id); return SLOTS.get(id); }
    public static void updateLayout(StudioId id,Layout layout) {
        StudioView old=view(id).orElse(new StudioView(id,false,"",false,Layout.EMPTY,null,slot(id)));
        put(new StudioView(id,old.own(),old.ownerName(),old.online(),layout,old.publicState(),old.slot()));
    }
    public static void updateState(StudioId id,PublicStudioState state) {
        StudioView old=view(id).orElse(new StudioView(id,false,"",false,Layout.EMPTY,null,slot(id)));
        put(new StudioView(id,old.own(),old.ownerName(),old.online(),old.layout(),state,old.slot()));
    }
    public static void updatePresence(StudioId id,String name,boolean online) {
        StudioView old=view(id).orElse(new StudioView(id,false,"",false,Layout.EMPTY,null,slot(id)));
        put(new StudioView(id,old.own(),name,online,old.layout(),old.publicState(),old.slot()));
    }
    public static void setOverlay(@Nullable StudioId id) { overlay=id; }
    public static void remove(StudioId id) {
        VIEWS.remove(id); VIEW_OPTIONS.remove(id); if(id.equals(ownId)) cachedOwn=null; PLOTS.remove(id); if(id.equals(overlay)) overlay=null;
        plotSnapshot=PLOTS.values().toArray(Plot[]::new); viewSnapshot=VIEWS.values().toArray(StudioView[]::new);
        LISTENERS.forEach(l->l.accept(id,null));
    }
    public static void reset() {
        for(StudioId id:List.copyOf(VIEWS.keySet())) remove(id);
        PLOTS.clear(); plotSnapshot=new Plot[0]; viewSnapshot=new StudioView[0]; SLOTS.clear(); VIEW_OPTIONS.clear(); cachedOwn=null; nextSlot=1; overlay=null; ownId=StudioId.LOCAL; Anchors.setSelf(StudioId.LOCAL);
    }
}
