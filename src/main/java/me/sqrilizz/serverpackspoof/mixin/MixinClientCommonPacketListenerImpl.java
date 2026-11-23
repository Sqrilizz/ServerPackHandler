package me.sqrilizz.serverpackspoof.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListenerImpl {
    @Shadow
    @Final
    protected Connection connection;

    @Shadow
    public abstract void send(Packet<?> packet);

    @Unique
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ServerPackSpoofer-Scheduler");
        t.setDaemon(true);
        return t;
    });

    @Unique
    private final Map<UUID, List<ScheduledFuture<?>>> pendingPackTasks = new ConcurrentHashMap<>();

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void onResourcePackPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        UUID id = packet.id();

        cancelPendingTasks(id);

        if (!connection.isConnected()) {
            ci.cancel();
            return;
        }

        List<ScheduledFuture<?>> tasks = Collections.synchronizedList(new ArrayList<>());
        pendingPackTasks.put(id, tasks);

        long acceptDelay = 40 + ThreadLocalRandom.current().nextInt(60);
        long downloadDelay = acceptDelay + 180 + ThreadLocalRandom.current().nextInt(140);
        long loadDelay = downloadDelay + 280 + ThreadLocalRandom.current().nextInt(240);

        ScheduledFuture<?> acceptFuture = EXECUTOR.schedule(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (connection.isConnected() && pendingPackTasks.containsKey(id)) {
                        send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.ACCEPTED));
                    }
                });
            }
        }, acceptDelay, TimeUnit.MILLISECONDS);
        tasks.add(acceptFuture);

        ScheduledFuture<?> downloadFuture = EXECUTOR.schedule(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (connection.isConnected() && pendingPackTasks.containsKey(id)) {
                        send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.DOWNLOADED));
                    }
                });
            }
        }, downloadDelay, TimeUnit.MILLISECONDS);
        tasks.add(downloadFuture);

        ScheduledFuture<?> loadFuture = EXECUTOR.schedule(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    if (connection.isConnected() && pendingPackTasks.containsKey(id)) {
                        send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
                        pendingPackTasks.remove(id);
                        if (mc.gui != null && mc.gui.chatListener() != null) {
                            mc.gui.chatListener().handleSystemMessage(
                                Component.literal("§8[§bPackSpoofer§8] §aServer resource pack acknowledged."),
                                false
                            );
                        }
                    }
                });
            }
        }, loadDelay, TimeUnit.MILLISECONDS);
        tasks.add(loadFuture);

        ci.cancel();
    }

    @Inject(method = "handleResourcePackPop", at = @At("HEAD"), cancellable = true)
    private void onResourcePackPop(ClientboundResourcePackPopPacket packet, CallbackInfo ci) {
        if (packet.id().isPresent()) {
            cancelPendingTasks(packet.id().get());
        } else {
            cancelAllPendingTasks();
        }
        ci.cancel();
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void onDisconnectCleanup(DisconnectionDetails details, CallbackInfo ci) {
        cancelAllPendingTasks();
    }

    @Unique
    private void cancelPendingTasks(UUID id) {
        List<ScheduledFuture<?>> tasks = pendingPackTasks.remove(id);
        if (tasks != null) {
            synchronized (tasks) {
                for (ScheduledFuture<?> task : tasks) {
                    task.cancel(false);
                }
            }
        }
    }

    @Unique
    private void cancelAllPendingTasks() {
        for (List<ScheduledFuture<?>> tasks : pendingPackTasks.values()) {
            if (tasks != null) {
                synchronized (tasks) {
                    for (ScheduledFuture<?> task : tasks) {
                        task.cancel(false);
                    }
                }
            }
        }
        pendingPackTasks.clear();
    }
}
