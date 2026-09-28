package com.maxlananas.fawebim.fabric.mixin;

import com.maxlananas.fawebim.fabric.FabricInteractions;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns an arm swing into a WorldEdit "left click air" event.
 *
 * <p>Fabric API has no callback for swinging at nothing, and FAWE needs it: the
 * shatter, erode and blob brushes act on the left click, and {@code /tool}
 * bindings can too. WorldEdit's own Fabric adapter injects into the same
 * method, so this mirrors its behaviour: a swing that belongs to block mining
 * is ignored, everything else is handed to the engine.</p>
 *
 * <p>The injections sit at the head of the handlers, before the game's own
 * {@code PacketUtils.ensureRunningOnSameThread}: a handler is first called on
 * the network thread, which that check turns back to be called again on the
 * server thread. Only the server thread's call acts, so a click edits the
 * world from the thread that owns it, once, and a scroll moves a binding one
 * step, not two.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    @Unique
    private int bim$ignoreSwingPackets;

    /** True on the server thread, which is where the handler's work happens. */
    @Unique
    private boolean bim$onServerThread() {
        return this.player.level().getServer().isSameThread();
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void bim$onAnimate(ServerboundSwingPacket packet, CallbackInfo callback) {
        if (!bim$onServerThread()) {
            return;
        }
        if (!this.player.gameMode.isDestroyingBlock) {
            if (this.bim$ignoreSwingPackets > 0) {
                this.bim$ignoreSwingPackets--;
            } else {
                FabricInteractions.onLeftClickAir(this.player);
            }
        }
    }

    /**
     * Scrolling the mouse wheel moves the hotbar, which is how {@code /tool scroll}
     * reaches the server: the binding gets one step and the slot is put back, so
     * the player keeps the item they were holding.
     */
    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"))
    private void bim$onSetCarriedItem(ServerboundSetCarriedItemPacket packet, CallbackInfo callback) {
        if (!bim$onServerThread()) {
            return;
        }
        int previous = this.player.getInventory().getSelectedSlot();
        int slot = packet.getSlot();
        if (slot == previous || slot < 0 || slot > 8) {
            return;
        }
        if (FabricInteractions.onSlotChange(this.player, slot, previous)) {
            this.player.getInventory().setSelectedSlot(previous);
            this.player.connection.send(new ClientboundSetHeldSlotPacket(previous));
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void bim$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        if (!bim$onServerThread()) {
            return;
        }
        switch (packet.getAction()) {
            case DROP_ITEM, DROP_ALL_ITEMS, START_DESTROY_BLOCK -> this.bim$ignoreSwingPackets++;
            default -> {
            }
        }
    }
}
