package me.sqrilizz.serverpackspoof.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListenerImpl {
    @Shadow
    public abstract void send(Packet<?> packet);

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ServerPackSpoofer-Thread");
        t.setDaemon(true);
        return t;
    });

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void onResourcePackPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        UUID id = packet.id();

        send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.ACCEPTED));

        EXECUTOR.schedule(() -> {
            try {
                send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.DOWNLOADED));
            } catch (Exception ignored) {}
        }, 250, TimeUnit.MILLISECONDS);

        EXECUTOR.schedule(() -> {
            try {
                send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gui != null && mc.gui.chatListener() != null) {
                    mc.gui.chatListener().handleSystemMessage(Component.literal("§8[§bPackSpoofer§8] §aServer resource pack spoofed."), false);
                }
            } catch (Exception ignored) {}
        }, 500, TimeUnit.MILLISECONDS);

        ci.cancel();
    }

    @Inject(method = "handleResourcePackPop", at = @At("HEAD"), cancellable = true)
    private void onResourcePackPop(ClientboundResourcePackPopPacket packet, CallbackInfo ci) {
        ci.cancel();
    }
}
