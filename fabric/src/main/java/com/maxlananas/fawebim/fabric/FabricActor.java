package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.World;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An {@link Actor} backed by a player, or by the server console.
 *
 * <p>The engine never touches Minecraft types, so this class is the only place
 * that knows how to read a player's position, direction, hotbar and reach, and
 * how to send them a message.</p>
 */
public final class FabricActor implements Actor {

    private final ServerPlayer player;
    private final CommandSourceStack source;
    private final FabricWorld world;

    public FabricActor(ServerPlayer player) {
        this.player = player;
        this.source = player.createCommandSourceStack();
        this.world = new FabricWorld((ServerLevel) player.level());
    }

    /** The console / command block variant (no position, no inventory). */
    public FabricActor(CommandSourceStack source, ServerLevel level) {
        this.player = null;
        this.source = source;
        this.world = new FabricWorld(level);
    }

    public ServerPlayer player() {
        return player;
    }

    @Override
    public String name() {
        return player != null ? player.getGameProfile().getName() : "CONSOLE";
    }

    @Override
    public UUID uuid() {
        return player != null ? player.getUUID() : new UUID(0, 0);
    }

    @Override
    public boolean isPlayer() {
        return player != null;
    }

    @Override
    public LocalSession session() {
        return SessionManager.get().of(uuid());
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public BlockVector3 position() {
        if (player == null) {
            BlockPos pos = source.getEntity() instanceof net.minecraft.world.entity.Entity entity
                    ? entity.blockPosition() : BlockPos.ZERO;
            return new BlockVector3(pos.getX(), pos.getY(), pos.getZ());
        }
        BlockPos pos = player.blockPosition();
        return new BlockVector3(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public Vector3 direction() {
        if (player == null) {
            return new Vector3(0, 0, 1);
        }
        Vec3 look = player.getLookAngle();
        return new Vector3(look.x, look.y, look.z);
    }

    @Override
    public Direction facing() {
        if (player == null) {
            return Direction.NORTH;
        }
        return switch (player.getDirection()) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
        };
    }

    @Override
    public double pitch() {
        return player == null ? 0 : player.getXRot();
    }

    @Override
    public double yaw() {
        return player == null ? 0 : player.getYRot();
    }

    @Override
    public double reachDistance() {
        if (player == null) {
            return 5.0;
        }
        // FAWE's far wand reaches further; vanilla reach is 4.5 blocks.
        return player.isCreative() ? 5.0 : 4.5;
    }

    @Override
    public void message(Msg message) {
        source.sendSuccess(() -> FabricMessages.component(message), false);
    }

    @Override
    public boolean hasPermission(String permission) {
        // Single player: everything is allowed unless the player is not op and
        // the game is in a restricted mode.
        if (player == null) {
            return true;
        }
        return source.hasPermission(2) || !player.getServer().isDedicatedServer();
    }

    @Override
    public String heldItem() {
        return player == null ? null : FabricMessages.heldItem(player);
    }

    @Override
    public List<Integer> hotbarBlocks() {
        if (player == null) {
            return List.of();
        }
        List<Integer> blocks = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var block = net.minecraft.world.level.block.Block.byItem(stack.getItem());
            if (block != net.minecraft.world.level.block.Blocks.AIR) {
                blocks.add((int) net.minecraft.world.level.block.Block.getId(block.defaultBlockState()));
            }
        }
        return blocks;
    }

    @Override
    public boolean giveWand(String item) {
        if (player == null) {
            return false;
        }
        var id = net.minecraft.resources.ResourceLocation.tryParse(item);
        if (id == null) {
            return false;
        }
        var mcItem = BuiltInRegistries.ITEM.getValue(id);
        if (mcItem == null) {
            return false;
        }
        ItemStack stack = new ItemStack(mcItem);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        return true;
    }

    @Override
    public boolean teleport(double x, double y, double z) {
        if (player == null) {
            return false;
        }
        player.teleportTo(x, y, z);
        return true;
    }

    @Override
    public void updateSelectionOutline() {
        // The selection is drawn by the server-side particle preview below.
        SelectionPreview.refresh(this);
    }

    public CommandSourceStack source() {
        return source;
    }
}
