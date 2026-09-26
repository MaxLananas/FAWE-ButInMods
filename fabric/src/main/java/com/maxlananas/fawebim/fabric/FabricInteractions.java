package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.region.SelectorLimits;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.tool.Tool;
import com.maxlananas.fawebim.core.tool.Tools;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** The item a notice last named, so a wrong item is said once and not per click. */
    private static final Map<UUID, String> LAST_NOTICE = new ConcurrentHashMap<>();

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
        LAST_NOTICE.remove(uuid);
    }

    /**
     * The item a brush or a tool is waiting for when the player holds another.
     * WorldEdit keeps one tool per item, so a click with the wrong item does
     * nothing at all; naming the item turns that silence into a state the player
     * can act on.
     */
    private static String waitingItem(LocalSession session, String held) {
        Map<String, Object> bindings = session.getBindings();
        if (bindings.containsKey("brush") && !held.equals(bindings.get("brush-item"))) {
            return String.valueOf(bindings.get("brush-item"));
        }
        if (bindings.containsKey("tool") && !held.equals(bindings.get("tool-item"))) {
            return String.valueOf(bindings.get("tool-item"));
        }
        return null;
    }

    private static void noteWaiting(FabricActor actor, String held) {
        ServerPlayer player = actor.player();
        if (player == null) {
            return;
        }
        String waiting = waitingItem(actor.session(), held);
        if (waiting == null) {
            LAST_NOTICE.remove(player.getUUID());
            return;
        }
        if (!waiting.equals(LAST_NOTICE.put(player.getUUID(), waiting))) {
            actor.message(Msg.warn("The brush is bound to " + waiting + ", you are holding " + held + "."));
        }
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
            if (CommandRegistry.interact(actor, "tool " + tool.name(), () -> tool.onLeftClick(context))) {
                return handled(player);
            }
        }

        // 2. Super-pickaxe: FAWE binds it to every pickaxe, not to the wand, and
        // it only acts once it has been turned on. A left click with anything
        // else - the wand included - falls through to the selection.
        if (session.isSuperPickaxeEnabled() && isPickaxe(held)) {
            BlockVector3 start = FabricMessages.blockVector(pos);
            return CommandRegistry.interact(actor, "super pickaxe", () -> superPickaxe(actor, start))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
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
            return applyBrush(actor, brush, aimedBlock(player));
        }
        Tool tool = Tools.current(session);
        if (tool != null && bound(session, "tool-item", held)) {
            Tool.ToolContext context = new Tool.ToolContext(actor, aimedBlock(player), actor.facing(), null);
            return CommandRegistry.interact(actor, "tool " + tool.name(), () -> tool.onSwing(context));
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
            return applyBrush(actor, brush, landing(player, pos, face))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        // 2. Tools.
        Tool tool = Tools.current(session);
        if (tool != null && bound(session, "tool-item", held)) {
            Tool.ToolContext context = new Tool.ToolContext(actor, FabricMessages.blockVector(pos),
                    FabricMessages.direction(face), null);
            if (CommandRegistry.interact(actor, "tool " + tool.name(), () -> tool.onRightClick(context))) {
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

        // Nothing claimed the click: if a brush or a tool is waiting for another
        // item, say which one instead of doing nothing silently.
        noteWaiting(actor, held);
        return InteractionResult.PASS;
    }

    /** {@code /tool} bindings use the item callbacks too. */
    public static InteractionResult onUseItem(ServerPlayer player, ItemStack stack) {
        if (recentlyHandled(player)) {
            return InteractionResult.PASS;
        }
        FabricActor actor = new FabricActor(player);
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);

        // A right click in the air with a brush of the held item acts on what the
        // player is looking at, which is what WorldEdit's right-click-air branch
        // does; without it a click that lands one pixel above a block does nothing.
        Brush brush = BrushFactory.current(session);
        if (brush != null && bound(session, "brush-item", held)) {
            return applyBrush(actor, brush, aimedBlock(player))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        Tool tool = Tools.current(session);
        if (tool == null || !bound(session, "tool-item", held)) {
            noteWaiting(actor, held);
            return InteractionResult.PASS;
        }
        Tool.ToolContext context = new Tool.ToolContext(actor, aimedBlock(player), actor.facing(), null);
        return CommandRegistry.interact(actor, "tool " + tool.name(), () -> tool.onRightClick(context))
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * The block the crosshair covers. {@code pick} is the game's own ray trace
     * from the eyes along the view vector, so the answer is the block the player
     * is aiming at - a trace written next to it can disagree with the crosshair
     * by a block and put a brush where the player stands instead of where they
     * look.
     */
    private static net.minecraft.world.phys.BlockHitResult aim(ServerPlayer player, double reach) {
        net.minecraft.world.phys.HitResult hit = player.pick(reach, 1.0F, false);
        return hit instanceof net.minecraft.world.phys.BlockHitResult block ? block : null;
    }

    /** Where a click lands: the block under the crosshair, or the clicked face's neighbour. */
    private static BlockVector3 landing(ServerPlayer player, BlockPos pos,
                                        net.minecraft.core.Direction face) {
        double reach = Math.max(5.0, Config.get().maxBrushRange);
        net.minecraft.world.phys.BlockHitResult aimed = aim(player, reach);
        if (aimed != null) {
            net.minecraft.core.Direction side = aimed.getDirection();
            return FabricMessages.blockVector(aimed.getBlockPos())
                    .add(side.getStepX(), side.getStepY(), side.getStepZ());
        }
        return FabricMessages.blockVector(pos).add(face.getStepX(), face.getStepY(), face.getStepZ());
    }

    /** The block under the crosshair, never the one the player stands in. */
    private static BlockVector3 aimedBlock(ServerPlayer player) {
        double reach = Math.max(5.0, Config.get().maxBrushRange);
        net.minecraft.world.phys.BlockHitResult aimed = aim(player, reach);
        if (aimed != null) {
            return FabricMessages.blockVector(aimed.getBlockPos());
        }
        // Nothing under the crosshair: the end of the reach is still in front of
        // the player, where the brush belongs.
        net.minecraft.world.phys.Vec3 end = player.getEyePosition(1.0F)
                .add(player.getViewVector(1.0F).scale(reach));
        return new BlockVector3((int) Math.floor(end.x), (int) Math.floor(end.y), (int) Math.floor(end.z));
    }

    /** True when the session's binding for {@code key} matches the held item. */
    private static boolean bound(LocalSession session, String key, String held) {
        Object value = session.getBindings().get(key);
        return held != null && held.equals(value);
    }

    /**
     * Runs a brush stroke with the guarantees of a command: its edit is closed
     * whatever happens, and a failure - the block limit, a missing clipboard, a
     * bug - is answered in chat instead of escaping into the packet handler.
     */
    private static boolean applyBrush(FabricActor actor, Brush brush, BlockVector3 position) {
        return CommandRegistry.interact(actor, "brush", () -> stroke(actor, brush, position));
    }

    private static boolean stroke(FabricActor actor, Brush brush, BlockVector3 position) {
        EditSession session = new EditSession(actor.world(), actor.session(), "brush");
        int changed;
        try {
            changed = com.maxlananas.fawebim.core.brush.Brushes.apply(brush, session, position, actor);
        } finally {
            session.close();
        }
        if (changed > 0 && actor.player() != null) {
            markHandled(actor.player());
        }
        if (changed > 0) {
            actor.message(Msg.success("Brush changed ")
                    .append(Msg.value(Msg.formatNumber(changed) + " block(s)"))
                    .append(" around ").append(Msg.value(position)));
        } else {
            // A brush that ran and changed nothing used to be completely silent,
            // which is indistinguishable from a click that never arrived.
            actor.message(Msg.warn("The brush changed no block around ").append(Msg.value(position)));
        }
        return changed > 0;
    }

    /** The six items WorldEdit's {@code Player#isHoldingPickAxe} answers for. */
    private static boolean isPickaxe(String item) {
        return item != null && (item.equals("minecraft:wooden_pickaxe")
                || item.equals("minecraft:stone_pickaxe") || item.equals("minecraft:iron_pickaxe")
                || item.equals("minecraft:golden_pickaxe") || item.equals("minecraft:diamond_pickaxe")
                || item.equals("minecraft:netherite_pickaxe"));
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
        try {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        edit.setBlock(start.x() + x, start.y() + y, start.z() + z, BlockState.registry().air());
                    }
                }
            }
        } finally {
            edit.close();
        }
        ServerPlayer player = actor.player();
        // A single break drops what it broke; an area break only drops everything
        // when many-drop-items is on, which is how WorldEdit reads the pair.
        boolean drops = radius == 0 ? Config.get().superPickaxeDrop
                : Config.get().superPickaxeDrop && Config.get().superPickaxeManyDrop;
        if (drops && player != null) {
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
        com.maxlananas.fawebim.core.tool.Scroll scroll = brush.settings().getScrollAction();
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
