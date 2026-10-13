package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LayoutS2C(StudioId studio, int plotIndex, Layout layout) implements CustomPacketPayload {
    public LayoutS2C {
        MpText.cap("layout name",layout.name(),48); MpText.cap("anchors",layout.anchors().keySet(),512);
        for(String key:layout.anchors().keySet()) MpText.cap("anchor name",key,48);
    }
    public static final Type<LayoutS2C> TYPE = new Type<>(AgentCraft.id("layout_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf,LayoutS2C> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeUUID(v.studio().owner()); b.writeVarInt(v.plotIndex()); MpCodecs.layout(b,v.layout()); }, b->new LayoutS2C(MpCodecs.studio(b), MpCodecs.integer(b,0,Integer.MAX_VALUE), MpCodecs.layout(b)));
    public Type<LayoutS2C> type() { return TYPE; }
}
