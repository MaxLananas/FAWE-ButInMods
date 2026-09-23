package com.fawebutinmods.fabric.mixin;

import com.fawebutinmods.fabric.FabricInteractions;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
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
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    @Unique
    private int bim$ignoreSwingPackets;

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void bim$onAnimate(ServerboundSwingPacket packet, CallbackInfo callback) {
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
        int previous = this.player.getInventory().selected;
        int slot = packet.getSlot();
        if (slot == previous || slot < 0 || slot > 8) {
            return;
        }
        if (FabricInteractions.onSlotChange(this.player, slot, previous)) {
            this.player.getInventory().selected = previous;
            this.player.connection.send(new ClientboundSetCarriedItemPacket(previous));
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void bim$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        switch (packet.getAction()) {
            case DROP_ITEM, DROP_ALL_ITEMS, START_DESTROY_BLOCK -> this.bim$ignoreSwingPackets++;
            default -> {
            }
        }
    }
}
