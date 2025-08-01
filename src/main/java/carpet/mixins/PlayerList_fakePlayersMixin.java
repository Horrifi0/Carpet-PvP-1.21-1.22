package carpet.mixins;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import carpet.patches.NetHandlerPlayServerFake;
import carpet.patches.EntityPlayerMPFake;

@Mixin(PlayerList.class)
public abstract class PlayerList_fakePlayersMixin
{
    @Shadow
    @Final
    private MinecraftServer server;

    // 1.21.8: load(ServerPlayer) -> load(ServerPlayer, ProblemReporter) and return type is CompoundTag
    // Keep old injection signature optional if remapped differently
    // 1.21.8: load signature gained a ProblemReporter parameter.
    // Provide both overloads; mark as optional so missing one won't crash.
    @Inject(method = "load", at = @At(value = "RETURN", shift = At.Shift.BEFORE), require = 0)
    private void fixStartingPos(ServerPlayer serverPlayerEntity_1, net.minecraft.util.ProblemReporter reporter, CallbackInfoReturnable<CompoundTag> cir)
    {
        if (serverPlayerEntity_1 instanceof EntityPlayerMPFake)
        {
            ((EntityPlayerMPFake) serverPlayerEntity_1).fixStartingPosition.run();
        }
    }

    // Remove legacy two-arg overload to avoid descriptor mismatch on 1.21.8

    @Redirect(method = "placeNewPlayer", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl replaceNetworkHandler(MinecraftServer server, Connection clientConnection, ServerPlayer playerIn, CommonListenerCookie cookie)
    {
        if (playerIn instanceof EntityPlayerMPFake fake)
        {
            return new NetHandlerPlayServerFake(this.server, clientConnection, fake, cookie);
        }
        else
        {
            return new ServerGamePacketListenerImpl(this.server, clientConnection, playerIn, cookie);
        }
    }

    @Redirect(method = "respawn", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    public ServerPlayer makePlayerForRespawn(MinecraftServer minecraftServer, ServerLevel serverLevel, GameProfile gameProfile, ClientInformation cli, ServerPlayer serverPlayer, boolean i)
    {
        if (serverPlayer instanceof EntityPlayerMPFake) {
            return EntityPlayerMPFake.respawnFake(minecraftServer, serverLevel, gameProfile, cli);
        }
        return new ServerPlayer(minecraftServer, serverLevel, gameProfile, cli);
    }
}
