package dev.agentcraft.mp.net;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors.*;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import java.util.function.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** All size checks precede allocation or traversal. No handler sees unchecked input. */
final class MpCodecs {
    private MpCodecs() {}
    static <T> StreamCodec<RegistryFriendlyByteBuf,T> codec(BiConsumer<RegistryFriendlyByteBuf,T> writer,Function<RegistryFriendlyByteBuf,T> reader) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf b) {
                try { return reader.apply(b); }
                catch(RuntimeException e) { throw new DecoderException("invalid AgentCraft payload",e); }
            }
            public void encode(RegistryFriendlyByteBuf b,T v) { writer.accept(b,v); }
        };
    }
    static String text(RegistryFriendlyByteBuf b,int cap) { return MpText.sanitize(b.readUtf(cap),cap); }
    static int integer(RegistryFriendlyByteBuf b,int min,int max) {
        int n=b.readVarInt(); if(n<min || n>max) throw new IllegalArgumentException("integer out of range"); return n;
    }
    /**
     * A hello's protocol is read first, and the rest of another protocol's hello is skipped, never parsed: the game
     * disconnects on unread bytes and on a decoder error, which would end the connection before the versions are compared.
     * The mismatch carries the sender's protocol and placeholders; the receiver refuses it by the protocol alone.
     */
    static <T> T hello(RegistryFriendlyByteBuf b,IntFunction<T> mismatch,IntFunction<T> same) {
        int protocol=integer(b,0,Integer.MAX_VALUE);
        if(protocol==MpProtocol.VERSION) return same.apply(protocol);
        b.skipBytes(b.readableBytes()); return mismatch.apply(protocol);
    }
    static StudioId studio(RegistryFriendlyByteBuf b) { return StudioId.of(b.readUUID()); }
    static boolean bool(RegistryFriendlyByteBuf b) {
        int n=b.readUnsignedByte(); if(n>1) throw new IllegalArgumentException("invalid boolean"); return n==1;
    }
    static ServerInfo info(RegistryFriendlyByteBuf b) {
        int stride=integer(b,96,1048576); if(stride%16!=0) throw new IllegalArgumentException("unaligned stride");
        return new ServerInfo(stride,integer(b,0,1024),integer(b,1,1000),integer(b,1,1000));
    }
    static void info(RegistryFriendlyByteBuf b,ServerInfo info) { b.writeVarInt(info.plotStride()); b.writeVarInt(info.relayRadiusChunks()); b.writeVarInt(info.publicStatePerSecond()); b.writeVarInt(info.intentsPerSecond()); }
    static double coordinate(RegistryFriendlyByteBuf b) {
        double n=b.readDouble(); if(!Double.isFinite(n) || Math.abs(n)>30000000) throw new IllegalArgumentException("invalid coordinate"); return n;
    }
    static float angle(RegistryFriendlyByteBuf b,boolean pitch) {
        float n=b.readFloat(); if(!Float.isFinite(n) || Math.abs(n)>(pitch ? 90 : 360)) throw new IllegalArgumentException("invalid angle"); return n;
    }
    static Layout layout(RegistryFriendlyByteBuf b) {
        String name=text(b,48); long rev=b.readVarLong(); if(rev<0) throw new IllegalArgumentException("negative revision");
        Bounds bounds=null;
        if(bool(b)) {
            int x=b.readInt(),y=b.readInt(),z=b.readInt(),xx=b.readInt(),yy=b.readInt(),zz=b.readInt();
            if(x>xx || y>yy || z>zz || Math.abs((long)x)>30000000 || Math.abs((long)xx)>30000000 || Math.abs((long)z)>30000000 || Math.abs((long)zz)>30000000 || y < -20000000 || yy > 20000000) throw new IllegalArgumentException("invalid bounds");
            bounds=new Bounds(x,y,z,xx,yy,zz);
        }
        int count=integer(b,0,512); Map<String,Anchor> map=new LinkedHashMap<>();
        for(int i=0;i<count;i++) {
            String key=text(b,48); Anchor a=new Anchor(key,coordinate(b),coordinate(b),coordinate(b),angle(b,false),angle(b,true));
            if(map.put(key,a)!=null) throw new IllegalArgumentException("duplicate anchor");
        }
        return new Layout(name,rev,bounds,Collections.unmodifiableMap(map));
    }
    static void layout(RegistryFriendlyByteBuf b,Layout l) {
        b.writeUtf(l.name()); b.writeVarLong(l.revision()); b.writeBoolean(l.bounds()!=null);
        if(l.bounds()!=null) { Bounds v=l.bounds(); b.writeInt(v.minX()); b.writeInt(v.minY()); b.writeInt(v.minZ()); b.writeInt(v.maxX()); b.writeInt(v.maxY()); b.writeInt(v.maxZ()); }
        b.writeVarInt(l.anchors().size()); l.anchors().forEach((key,a)-> { b.writeUtf(key); b.writeDouble(a.x()); b.writeDouble(a.y()); b.writeDouble(a.z()); b.writeFloat(a.yaw()); b.writeFloat(a.pitch()); });
    }
    static PublicStudioState state(RegistryFriendlyByteBuf b) {
        PublicStudioState state=PublicJson.fromJson(b.readUtf(30000));
        if(PublicJson.toJson(state).toString().length()>30000) throw new IllegalArgumentException("JSON cap exceeded");
        return state;
    }
    static void state(RegistryFriendlyByteBuf b,PublicStudioState s) { b.writeUtf(PublicJson.toJson(PublicJson.fromJson(PublicJson.toJson(s))).toString(),30000); }
    static PublicEvent event(RegistryFriendlyByteBuf b) { return PublicJson.eventFromJson(b.readUtf(2048)); }
    static void event(RegistryFriendlyByteBuf b,PublicEvent e) { b.writeUtf(PublicJson.toJson(e).toString(),2048); }
    static WorldIntent intent(RegistryFriendlyByteBuf b) { return PublicJson.intentFromJson(b.readUtf(16384)); }
    static void intent(RegistryFriendlyByteBuf b,WorldIntent w) { b.writeUtf(PublicJson.toJson(w).toString(),16384); }
}
