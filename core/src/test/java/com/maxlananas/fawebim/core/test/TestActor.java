package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An actor that records the messages it receives, used by the self-tests. */
public final class TestActor implements Actor {

    private final String name;
    private final UUID uuid;
    private final World world;
    private final LocalSession session;
    private BlockVector3 position;
    private double yaw = -90;
    private double pitch = 0;
    private String heldItem = "minecraft:wooden_axe";
    private final List<String> messages = new ArrayList<>();
    private boolean screen;
    private final boolean player;

    public TestActor(String name, World world, BlockVector3 position) {
        this(name, world, position, true);
    }

    private TestActor(String name, World world, BlockVector3 position, boolean player) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(name.getBytes());
        this.world = world;
        this.position = position;
        this.player = player;
        this.session = SessionManager.get().of(uuid);
    }

    /**
     * The server console: not a player, and nowhere to stand. The commands
     * WorldEdit binds to a Player refuse it, and the ones that only need
     * somewhere to build answer with the selection.
     */
    public static TestActor positionlessConsole(World world) {
        return positionlessConsole("CONSOLE", world);
    }

    /**
     * The same source under a name of its own, for a test that needs a session
     * of its own: sessions are keyed by the actor's id, and a mask left set by
     * one test would filter the writes of the next one.
     */
    public static TestActor positionlessConsole(String name, World world) {
        return new TestActor(name, world, null, false);
    }

    /** Makes this actor answer the configuration screen hook, as a real client does. */
    public void setScreenAvailable(boolean value) {
        this.screen = value;
    }

    @Override
    public boolean openConfigurationScreen() {
        return screen;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean isPlayer() {
        return player;
    }

    @Override
    public LocalSession session() {
        return session;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public BlockVector3 position() {
        return position;
    }

    public void setPosition(BlockVector3 position) {
        this.position = position;
    }

    @Override
    public Vector3 direction() {
        return new Vector3(
                -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)),
                -Math.sin(Math.toRadians(pitch)),
                Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)));
    }

    @Override
    public Direction facing() {
        double degrees = ((yaw % 360) + 360) % 360;
        if (degrees < 45 || degrees >= 315) {
            return Direction.SOUTH;
        }
        if (degrees < 135) {
            return Direction.WEST;
        }
        if (degrees < 225) {
            return Direction.NORTH;
        }
        return Direction.EAST;
    }

    @Override
    public double yaw() {
        return yaw;
    }

    @Override
    public double pitch() {
        return pitch;
    }

    public void setYaw(double yaw) {
        this.yaw = yaw;
    }

    public void setPitch(double pitch) {
        this.pitch = pitch;
    }

    @Override
    public void message(Msg message) {
        messages.add(message.raw());
    }

    @Override
    public String heldItem() {
        return heldItem;
    }

    public void setHeldItem(String heldItem) {
        this.heldItem = heldItem;
    }

    @Override
    public java.util.List<Integer> hotbarBlocks() {
        return List.of(com.maxlananas.fawebim.core.world.BlockState.registry()
                .defaultState("minecraft:stone"));
    }

    @Override
    public boolean giveWand(String item) {
        messages.add("Gave " + item);
        heldItem = item;
        return true;
    }

    @Override
    public boolean teleport(double x, double y, double z) {
        position = new BlockVector3((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return true;
    }

    public List<String> messages() {
        return messages;
    }

    public String lastMessage() {
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
    }

    public void clearMessages() {
        messages.clear();
    }
}
