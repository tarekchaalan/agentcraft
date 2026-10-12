package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PresenceS2C(StudioId studio, String ownerName, boolean online) implements CustomPacketPayload {
    public PresenceS2C { MpText.cap("ownerName",ownerName,16); }
    public static final Type<PresenceS2C> TYPE = new Type<>(AgentCraft.id("presence_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PresenceS2C> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeUUID(v.studio().owner()); b.writeUtf(v.ownerName()); b.writeBoolean(v.online()); }, b->new PresenceS2C(MpCodecs.studio(b), MpCodecs.text(b,16), MpCodecs.bool(b)));
    public Type<PresenceS2C> type() { return TYPE; }
}
