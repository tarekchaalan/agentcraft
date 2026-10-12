package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LayoutRemoveS2C(StudioId studio) implements CustomPacketPayload {
    public static final Type<LayoutRemoveS2C> TYPE = new Type<>(AgentCraft.id("layout_remove_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf,LayoutRemoveS2C> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeUUID(v.studio().owner()); }, b->new LayoutRemoveS2C(MpCodecs.studio(b)));
    public Type<LayoutRemoveS2C> type() { return TYPE; }
}
