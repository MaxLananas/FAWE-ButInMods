package com.maxlananas.fawebim.core.world;

import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.NbtCompound;

/**
 * A platform-neutral entity: its type key, serialised data and position.
 *
 * <p>Live entities carry the platform handle so that {@code /butcher} and
 * {@code //remove -e} can act on them, and their identity; entities loaded from
 * a schematic only have the serialised NBT. The data of a live entity is read
 * the first time it is asked for, on the server thread, so a command that only
 * looks at types and positions does not serialise every entity it considers.</p>
 */
public final class EntityData implements Cloneable {

    private String type;
    private NbtCompound nbt;
    private java.util.function.Supplier<NbtCompound> nbtSource;
    private Vector3 position;
    private Object handle;
    private String uuid;
    private boolean passenger;
    private boolean spawnable = true;

    public EntityData(String type, NbtCompound nbt, Vector3 position) {
        this.type = type;
        this.nbt = nbt;
        this.position = position;
    }

    public EntityData(Object handle, String type, NbtCompound nbt, Vector3 position) {
        this(type, nbt, position);
        this.handle = handle;
    }

    /**
     * A live entity whose data is read when first asked for.
     *
     * @param nbt reads the entity's data, or answers {@code null} for one the
     *            game does not save on its own (a player, a passenger, which
     *            travels in its vehicle's data)
     */
    public EntityData(Object handle, String type, java.util.function.Supplier<NbtCompound> nbt, Vector3 position) {
        this(type, (NbtCompound) null, position);
        this.handle = handle;
        this.nbtSource = nbt;
    }

    public String type() {
        return type;
    }

    public NbtCompound nbt() {
        if (nbtSource != null) {
            nbt = nbtSource.get();
            nbtSource = null;
        }
        return nbt;
    }
    public Vector3 position() {
        return position;
    }

    public void setPosition(Vector3 position) {
        this.position = position;
    }

    public Object handle() {
        return handle;
    }

    public void setHandle(Object handle) {
        this.handle = handle;
    }

    public boolean isSpawnable() {
        return spawnable;
    }

    public void setSpawnable(boolean spawnable) {
        this.spawnable = spawnable;
    }

    /** The identity of a live entity, which finds it again for an undo; {@code null} for one that is only data. */
    public String uuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    /**
     * True for a live entity riding another: it travels in its vehicle's data,
     * so a copy of both takes the vehicle only.
     */
    public boolean isPassenger() {
        return passenger;
    }

    public void setPassenger(boolean passenger) {
        this.passenger = passenger;
    }

    public EntityData offset(int dx, int dy, int dz) {
        return new EntityData(type, nbt(), position.add(dx, dy, dz));
    }

    /** A copy holding its own data, read now if it was not yet. */
    @Override
    public EntityData clone() {
        try {
            NbtCompound data = nbt();
            EntityData copy = (EntityData) super.clone();
            copy.nbt = data == null ? null : (NbtCompound) data.clone();
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public String toString() {
        return type + " at " + position;
    }
}
