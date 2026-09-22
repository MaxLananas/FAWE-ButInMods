package com.fawebutinmods.fabric.mixin;

import com.fawebutinmods.fabric.FabricInteractions;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
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
    private int faweButInMods$ignoreSwingPackets;

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void faweButInMods$onAnimate(ServerboundSwingPacket packet, CallbackInfo callback) {
        if (!this.player.gameMode.isDestroyingBlock) {
            if (this.faweButInMods$ignoreSwingPackets > 0) {
                this.faweButInMods$ignoreSwingPackets--;
            } else {
                FabricInteractions.onLeftClickAir(this.player);
            }
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void faweButInMods$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        switch (packet.getAction()) {
            case DROP_ITEM, DROP_ALL_ITEMS, START_DESTROY_BLOCK -> this.faweButInMods$ignoreSwingPackets++;
            default -> {
            }
        }
    }
}
