package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.region.SelectorLimits;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.tool.SuperPickaxe;
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
        if (!actor.mayEdit()) {
            return InteractionResult.PASS;
        }
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);

        // 1. Left-click brushes (shatter, erode, ...) and tools.
        Brush brush = BrushFactory.current(session);
        if (brush != null && brush.leftClick() && bound(session, "brush-item", held)) {
            return applyBrush(actor, brush, FabricMessages.blockVector(pos))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        Tool tool = Tools.forItem(session, held);
        if (tool != null) {
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
        if (held != null && held.equals(Config.get().wandItem) && session.isSelectionWandEnabled()) {
            session.setLastClickedPosition(FabricMessages.blockVector(pos));
            session.setLastClickedFace(FabricMessages.direction(face));
            session.getSelector(actor.world()).selectPrimary(FabricMessages.blockVector(pos),
                    SelectorLimits.unlimited());
            actor.message(Msg.result("Position 1", "set to " + Msg.value(FabricMessages.blockVector(pos)).raw()));
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
        if (!actor.mayEdit()) {
            return false;
        }
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);
        Brush brush = BrushFactory.current(session);
        if (brush != null && brush.leftClick() && bound(session, "brush-item", held)) {
            return applyBrush(actor, brush, aimedBlock(player));
        }
        Tool tool = Tools.forItem(session, held);
        if (tool != null) {
            Tool.ToolContext context = aimedContext(actor, player);
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
        if (!actor.mayEdit()) {
            return InteractionResult.PASS;
        }
        LocalSession session = actor.session();
        String held = FabricMessages.heldItem(player);

        // 1. Brushes.
        Brush brush = BrushFactory.current(session);
        if (brush != null && bound(session, "brush-item", held)) {
            return applyBrush(actor, brush, landing(player, pos, face))
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        // 2. Tools.
        Tool tool = Tools.forItem(session, held);
        if (tool != null) {
            Tool.ToolContext context = new Tool.ToolContext(actor, FabricMessages.blockVector(pos),
                    FabricMessages.direction(face), null);
            if (CommandRegistry.interact(actor, "tool " + tool.name(), () -> tool.onRightClick(context))) {
                return handled(player);
            }
        }

        // 3. Far wand: right-click extends the selection.
        if (held != null && held.equals(Config.get().wandItem) && session.isSelectionWandEnabled()
                && !player.isShiftKeyDown()) {
            session.setLastClickedPosition(FabricMessages.blockVector(pos));
            session.setLastClickedFace(FabricMessages.direction(face));
            session.getSelector(actor.world()).selectSecondary(FabricMessages.blockVector(pos),
                    SelectorLimits.unlimited());
            actor.message(Msg.result("Position 2", "set to " + Msg.value(FabricMessages.blockVector(pos)).raw()));
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
        if (!actor.mayEdit()) {
            return InteractionResult.PASS;
        }
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

        Tool tool = Tools.forItem(session, held);
        if (tool == null) {
            noteWaiting(actor, held);
            return InteractionResult.PASS;
        }
        Tool.ToolContext context = aimedContext(actor, player);
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

    /**
     * What a click in the air is about, for a tool: the block under the
     * crosshair and the face of it the crosshair meets, or, with no block in
     * reach, the end of the reach and no face.
     */
    private static Tool.ToolContext aimedContext(FabricActor actor, ServerPlayer player) {
        double reach = Math.max(5.0, Config.get().maxBrushRange);
        net.minecraft.world.phys.BlockHitResult aimed = aim(player, reach);
        if (aimed != null && aimed.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return new Tool.ToolContext(actor, FabricMessages.blockVector(aimed.getBlockPos()),
                    FabricMessages.direction(aimed.getDirection()), null);
        }
        return new Tool.ToolContext(actor, aimedBlock(player), null, null);
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
            actor.message(Msg.result("Brush", Msg.count(changed) + " block(s) changed around "
                    + Msg.value(position).raw()));
        } else {
            // A brush that ran and changed nothing used to be completely silent,
            // which is indistinguishable from a click that never arrived.
            actor.message(Msg.warn("The brush changed no block around " + Msg.value(position).raw()));
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

    /**
     * The super pickaxe: breaks what {@link SuperPickaxe} plans for the click
     * through an edit session, so {@code //undo} puts it back like any edit,
     * and drops what it broke the way breaking the block would when the
     * configuration asks for drops - {@code super-pickaxe-drop} for the single
     * pick, {@code super-pickaxe-many-drop} for the other two, as WorldEdit
     * reads them.
     */
    private static boolean superPickaxe(FabricActor actor, BlockVector3 start) {
        ServerPlayer player = actor.player();
        LocalSession session = actor.session();
        int mode = session.getSuperPickaxeMode();
        // The ceiling is read again at the click: it may have been lowered since
        // the mode was chosen.
        int ceiling = Math.min(Config.get().maxSuperPickaxeSize, SuperPickaxe.MAX_RANGE);
        double range = Math.max(0, Math.min(session.getSuperPickaxeRange(), ceiling));
        int[] targets = SuperPickaxe.targets(actor.world(), mode, range, start.x(), start.y(), start.z());
        if (targets.length == 0 || player == null) {
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        boolean drops = mode == SuperPickaxe.SINGLE ? Config.get().superPickaxeDrop
                : Config.get().superPickaxeManyDrop;
        int count = targets.length / 3;
        net.minecraft.world.level.block.state.BlockState[] states =
                new net.minecraft.world.level.block.state.BlockState[count];
        net.minecraft.world.level.block.entity.BlockEntity[] blockEntities =
                drops ? new net.minecraft.world.level.block.entity.BlockEntity[count] : null;
        boolean[] broken = new boolean[count];
        int air = BlockState.registry().air();
        EditSession edit = new EditSession(actor.world(), session, "superpickaxe");
        try {
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(targets[3 * i], targets[3 * i + 1], targets[3 * i + 2]);
                // The block is read before its own write: the edit only buffers
                // it, so the world still holds it and its block entity.
                states[i] = level.getBlockState(pos);
                if (drops) {
                    blockEntities[i] = level.getBlockEntity(pos);
                }
                broken[i] = edit.setBlock(pos.getX(), pos.getY(), pos.getZ(), air);
            }
        } finally {
            // A change limit reached halfway stops the loop: what was broken
            // before it is written, and drops, and the limit is still reported.
            edit.close();
            for (int i = 0; i < count; i++) {
                if (!broken[i]) {
                    continue;
                }
                BlockPos pos = new BlockPos(targets[3 * i], targets[3 * i + 1], targets[3 * i + 2]);
                if (drops) {
                    net.minecraft.world.level.block.Block.dropResources(states[i], level, pos, blockEntities[i]);
                }
                // The breaking particles and sound, for the clicked block only:
                // one per block of an area would be a packet per block.
                if (pos.getX() == start.x() && pos.getY() == start.y() && pos.getZ() == start.z()) {
                    level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(states[i]));
                }
            }
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
        if (!actor.mayEdit()) {
            return false;
        }
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
        if (actor.mayEdit() && actor.session().isDrawSelection()) {
            SelectionPreview.refresh(actor);
        }
    }

}
