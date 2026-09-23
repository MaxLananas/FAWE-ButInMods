package com.fawebutinmods.fabric;

import com.fawebutinmods.core.brush.Brush;
import com.fawebutinmods.core.brush.BrushFactory;
import com.fawebutinmods.core.command.CommandManager;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.platform.Config;
import com.fawebutinmods.core.region.SelectorLimits;
import com.fawebutinmods.core.session.LocalSession;
import com.fawebutinmods.core.tool.Tool;
import com.fawebutinmods.core.tool.Tools;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;


/**
 * Everything that happens when a player clicks: the selection wand, the
 * navigation wand, the brushes, the tools, the super-pickaxe and {@code /tool}.
 *
 * <p>WorldEdit needed mixins for this on Fabric; the mod only uses Fabric API's
 * public callbacks, so it stays compatible with other mods.</p>
 */
public final class FabricInteractions {

    /** Players whose click was already handled in this game tick. */
    private static final Map<UUID, Long> LAST_HANDLED = new ConcurrentHashMap<>();

    private FabricInteractions() {
    }

    /** Mirrors WorldEdit's interaction debouncer: one handled click per tick. */
    static boolean recentlyHandled(ServerPlayer player) {
        Long tick = LAST_HANDLED.get(player.getUUID());
        return tick != null && tick == gameTime(player);
    }

    static void markHandled(ServerPlayer player) {
        LAST_HANDLED.put(player.getUUID(), gameTime(player));
    }

    private static long gameTime(ServerPlayer player) {
        return player.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
    }

    /** Marks the click as handled so the follow-up callback in the same tick is ignored. */
    private static InteractionResult handled(ServerPlayer player) {
        markHandled(player);
        return InteractionResult.SUCCESS;
    }

    static void forget(UUID uuid) {
        LAST_HANDLED.remove(uuid);
    }

    /** Left-click a block. */
    public static InteractionResult onAttackBlock(ServerPlayer player, BlockPos pos,
                                                  net.minecraft.core.Direction face) {
        if (recentlyHandled(player)) {
            return InteractionResult.PASS;
        }
        FabricActor actor = new FabricActor(player);
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);

        // 1. Left-click brushes (shatter, erode, ...) and tools.
        Brush brush = BrushFactory.current(session);
        if (brush != null && brush.leftClick() && bound(session, "brush-item", held)) {
            return applyBrush(actor, brush, FabricMessages.blockVector(pos))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        Tool tool = Tools.current(session);
        if (tool != null && bound(session, "tool-item", held)) {
            Tool.ToolContext context = new Tool.ToolContext(actor, FabricMessages.blockVector(pos),
                    FabricMessages.direction(face), null);
            if (tool.onLeftClick(context)) {
                return handled(player);
            }
        }

        // 2. Super-pickaxe: break the area/recursive blocks.
        if (held != null && held.equals(Config.get().wandItem) && session.isSuperPickaxeEnabled()) {
            return superPickaxe(actor, FabricMessages.blockVector(pos)) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        // 3. The selection wand: first corner.
        if (held != null && held.equals(Config.get().wandItem)) {
            session.setLastClickedPosition(FabricMessages.blockVector(pos));
            session.setLastClickedFace(FabricMessages.direction(face));
            session.getSelector(actor.world()).selectPrimary(FabricMessages.blockVector(pos),
                    SelectorLimits.unlimited());
            actor.message(Msg.success("Position 1: ").append(Msg.value(FabricMessages.blockVector(pos))));
            actor.updateSelectionOutline();
            return handled(player);
        }
        // Anything else falls through to the arm swing, exactly like WorldEdit's
        // Fabric adapter does (block click then swing).
        return onLeftClickAir(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * Left click in the air, or the swing that follows a left click.
     *
     * <p>The mixin on the swing packet calls this: FAWE's shatter, erode and
     * blob brushes act on a left click even when the player is aiming at
     * nothing, and {@code /tool} bindings can use the swing too.</p>
     */
    public static boolean onLeftClickAir(ServerPlayer player) {
        FabricActor actor = new FabricActor(player);
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);
        Brush brush = BrushFactory.current(session);
        if (brush != null && brush.leftClick() && bound(session, "brush-item", held)) {
            BlockVector3 target = actor.world().getTargetBlock(actor, (int) actor.reachDistance());
            return applyBrush(actor, brush, target);
        }
        Tool tool = Tools.current(session);
        if (tool != null && bound(session, "tool-item", held)) {
            BlockVector3 target = actor.world().getTargetBlock(actor, (int) actor.reachDistance());
            return tool.onSwing(new Tool.ToolContext(actor, target, actor.facing(), null));
        }
        return false;
    }

    /** Right-click a block. */
    public static InteractionResult onUseBlock(ServerPlayer player, BlockPos pos, net.minecraft.core.Direction face) {
        if (recentlyHandled(player)) {
            return InteractionResult.PASS;
        }
        FabricActor actor = new FabricActor(player);
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);

        // 1. Brushes.
        Brush brush = BrushFactory.current(session);
        if (brush != null && bound(session, "brush-item", held)) {
            return applyBrush(actor, brush, FabricMessages.blockVector(pos).add(
                    face.getStepX(), face.getStepY(), face.getStepZ()))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        // 2. Tools.
        Tool tool = Tools.current(session);
        if (tool != null && bound(session, "tool-item", held)) {
            Tool.ToolContext context = new Tool.ToolContext(actor, FabricMessages.blockVector(pos),
                    FabricMessages.direction(face), null);
            if (tool.onRightClick(context)) {
                return handled(player);
            }
        }

        // 3. Far wand: right-click extends the selection.
        if (held != null && held.equals(Config.get().wandItem) && !player.isShiftKeyDown()) {
            session.setLastClickedPosition(FabricMessages.blockVector(pos));
            session.setLastClickedFace(FabricMessages.direction(face));
            session.getSelector(actor.world()).selectSecondary(FabricMessages.blockVector(pos),
                    SelectorLimits.unlimited());
            actor.message(Msg.success("Position 2: ").append(Msg.value(FabricMessages.blockVector(pos))));
            actor.updateSelectionOutline();
            return handled(player);
        }
        return InteractionResult.PASS;
    }

    /** {@code /tool} bindings use the item callbacks too. */
    public static InteractionResult onUseItem(ServerPlayer player, ItemStack stack) {
        if (recentlyHandled(player)) {
            return InteractionResult.PASS;
        }
        FabricActor actor = new FabricActor(player);
        LocalSession session = actor.session();
        Tool tool = Tools.current(session);
        String held = FabricMessages.heldItem(player);
        if (tool == null || !bound(session, "tool-item", held)) {
            return InteractionResult.PASS;
        }
        BlockVector3 target = actor.world().getTargetBlock(actor, 100);
        Tool.ToolContext context = new Tool.ToolContext(actor, target, actor.facing(), null);
        return tool.onRightClick(context) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** True when the session's binding for {@code key} matches the held item. */
    private static boolean bound(LocalSession session, String key, String held) {
        Object value = session.getBindings().get(key);
        return held != null && held.equals(value);
    }

    private static boolean applyBrush(FabricActor actor, Brush brush, BlockVector3 position) {
        EditSession session = new EditSession(actor.world(), actor.session(), "brush");
        int changed = brush.apply(session, position, actor);
        session.flushQueue();
        if (changed > 0 && actor.player() != null) {
            markHandled(actor.player());
        }
        if (changed > 0) {
            actor.message(Msg.success("Brush changed " + Msg.formatNumber(changed) + " block(s)"));
        }
        return changed > 0;
    }

    /** FAWE's super-pickaxe: instant break of an area or a whole tree. */
    private static boolean superPickaxe(FabricActor actor, BlockVector3 start) {
        LocalSession session = actor.session();
        if (session.getSuperPickaxeMode() == 2) {
            // Recursive mode removes whole trees, like FAWE's recursive pickaxe.
            return CommandManager.get().dispatch(actor, "//deltree");
        }
        EditSession edit = new EditSession(actor.world(), session, "superpickaxe", false);
        int radius = Math.max(0, session.getSuperPickaxeRadius());
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    edit.setBlock(start.x() + x, start.y() + y, start.z() + z, BlockState.registry().air());
                }
            }
        }
        edit.flushQueue();
        ServerPlayer player = actor.player();
        if (Config.get().superPickaxeDrop && player != null) {
            // Break particles/sound, exactly like a vanilla block break.
            player.level().levelEvent(2001, new BlockPos(start.x(), start.y(), start.z()),
                    net.minecraft.world.level.block.Block.getId(
                            player.level().getBlockState(new BlockPos(start.x(), start.y(), start.z()))));
        }
        return true;
    }

    /**
     * The client's hotbar moves when the player scrolls the mouse wheel, which is
     * the only scroll signal a vanilla server gets: FAWE turns it into one step
     * for whatever {@code /tool scroll} installed. Returning true means the
     * binding used the scroll, so the adapter puts the held slot back.
     */
    public static boolean onSlotChange(ServerPlayer player, int newSlot, int oldSlot) {
        if (player.isShiftKeyDown()) {
            return false;
        }
        FabricActor actor = new FabricActor(player);
        LocalSession session = actor.session();
        Brush brush = BrushFactory.current(session);
        if (brush == null || !bound(session, "brush-item", FabricMessages.heldItem(player))) {
            return false;
        }
        com.fawebutinmods.core.tool.Scroll scroll = brush.settings().getScrollAction();
        if (scroll == null) {
            return false;
        }
        int delta = newSlot - oldSlot;
        int amount = ((delta <= 4 && delta > 0) || delta < -4) ? 1 : -1;
        return scroll.increment(amount);
    }

    /** Used by {@code /brush command} and the tool bindings. */
    public static void tick(ServerPlayer player) {
        FabricActor actor = new FabricActor(player);
        if (actor.session().isDrawSelection()) {
            SelectionPreview.refresh(actor);
        }
    }

}
