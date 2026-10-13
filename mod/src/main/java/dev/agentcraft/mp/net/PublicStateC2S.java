package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record PublicStateC2S(PublicStudioState state) implements CustomPacketPayload {
    public static final Type<PublicStateC2S> TYPE = new Type<>(AgentCraft.id("public_state_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf,PublicStateC2S> CODEC = MpCodecs.codec(
        (b,v)-> { MpCodecs.state(b,v.state()); }, b->{
            try { return new PublicStateC2S(MpCodecs.state(b)); }
            catch(RuntimeException e) {
                MpLog.event(MpEvents.PUBLIC_STATE_REJECTED,"reason",MpReasons.DECODE_FAILED);
                throw e;
            }
        });
    public Type<PublicStateC2S> type() { return TYPE; }
}
