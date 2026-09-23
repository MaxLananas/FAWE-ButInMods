package com.maxlananas.fawebim.core.world;

import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.NbtCompound;

/**
 * A platform-neutral entity: its type key, serialised data and position.
 *
 * <p>Live entities carry the platform handle so that {@code /butcher} and
 * {@code //remove -e} can act on them; entities loaded from a schematic only
 * have the serialised NBT.</p>
 */
public final class EntityData implements Cloneable {

    private String type;
    private NbtCompound nbt;
    private Vector3 position;
    private Object handle;
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

    public String type() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public NbtCompound nbt() {
        return nbt;
    }

    public void setNbt(NbtCompound nbt) {
        this.nbt = nbt;
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

    /** Name used for duplicate-detection when pasting. */
    public String uuid() {
        if (nbt == null) {
            return null;
        }
        if (nbt.contains("UUID")) {
            return nbt.get("UUID").toString();
        }
        return nbt.getString("uuid", null);
    }

    public EntityData offset(int dx, int dy, int dz) {
        return new EntityData(type, nbt, position.add(dx, dy, dz));
    }

    @Override
    public EntityData clone() {
        try {
            EntityData copy = (EntityData) super.clone();
            copy.nbt = nbt == null ? null : (NbtCompound) nbt.clone();
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
