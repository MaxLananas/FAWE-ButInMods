package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.EntityData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the schematic formats share: how a clipboard's block entities and
 * entities become tags and back, and reading the numbers of files that were
 * not necessarily written by this mod.
 *
 * <p>A block entity in a clipboard is the compound the game saves for it, with
 * its type under {@code id} and without its position; the position is the
 * clipboard's key. An entity keeps its type and position in {@link EntityData}
 * and the rest of its data in the compound.</p>
 */
final class SchematicData {

    private static final Comparator<Map.Entry<BlockVector3, NbtCompound>> BLOCK_ORDER =
            Comparator.<Map.Entry<BlockVector3, NbtCompound>>comparingInt(entry -> entry.getKey().y())
                    .thenComparingInt(entry -> entry.getKey().z())
                    .thenComparingInt(entry -> entry.getKey().x());

    private SchematicData() {
    }

    /** The clipboard's block entities in the order their blocks are written, so a file does not depend on hashing. */
    static List<Map.Entry<BlockVector3, NbtCompound>> sortedBlockEntities(BlockArrayClipboard clipboard) {
        List<Map.Entry<BlockVector3, NbtCompound>> entries = new ArrayList<>(clipboard.blockEntities().entrySet());
        entries.sort(BLOCK_ORDER);
        return entries;
    }

    /** A copy of a block entity's data without its type and position, which the formats store apart. */
    static NbtCompound blockEntityData(NbtCompound source) {
        NbtCompound data = source.clone();
        data.remove("id");
        data.remove("Id");
        data.remove("x");
        data.remove("y");
        data.remove("z");
        data.remove("Pos");
        return data;
    }

    /** A block entity's type; a compound without one is typed after its block, as the game names them alike. */
    static String blockEntityId(NbtCompound source, BlockArrayClipboard clipboard, BlockVector3 pos) {
        String id = source.getString("id", source.getString("Id", null));
        return id != null ? id : BlockState.registry().name(clipboard.getBlock(pos.x(), pos.y(), pos.z()));
    }

    /**
     * The clipboard's form of a block entity read from a file, with the type
     * put back under {@code id}.
     *
     * @param nested true when the data is under {@code Data} (Sponge v3), false
     *               when it is the tag itself (Sponge v1/v2, MCEdit)
     */
    static NbtCompound readBlockEntity(NbtCompound tag, boolean nested) {
        String id = tag.getString("Id", tag.getString("id", null));
        NbtCompound data;
        if (nested) {
            NbtCompound inner = tag.getCompoundOrNull("Data");
            data = inner == null ? new NbtCompound() : blockEntityData(inner);
        } else {
            data = blockEntityData(tag);
            data.remove("ContentVersion");
        }
        if (id != null) {
            data.putString("id", id);
        }
        return data;
    }

    /** A copy of an entity's data without the type and position the formats store apart. */
    static NbtCompound entityData(EntityData entity) {
        NbtCompound data = entity.nbt() == null ? new NbtCompound() : entity.nbt().clone();
        data.remove("id");
        data.remove("Id");
        data.remove("Pos");
        return data;
    }

    /** An entity's position relative to the minimum corner, as the list of three doubles the formats use. */
    static List<Double> relativePosition(EntityData entity, BlockBox box) {
        Vector3 position = entity.position();
        return List.of(position.x() - box.minX(), position.y() - box.minY(), position.z() - box.minZ());
    }

    /**
     * An entity read from a file, or {@code null} when the tag has no type or
     * no position.
     *
     * @param nested as for {@link #readBlockEntity}
     */
    static EntityData readEntity(NbtCompound tag, boolean nested) {
        String id = tag.getString("Id", tag.getString("id", null));
        double[] pos = doubleTriple(tag.get("Pos"));
        if (id == null || pos == null) {
            return null;
        }
        NbtCompound inner = nested ? tag.getCompoundOrNull("Data") : tag;
        NbtCompound data = inner == null ? new NbtCompound() : inner.clone();
        data.remove("id");
        data.remove("Id");
        data.remove("Pos");
        return new EntityData(id, data, new Vector3(pos[0], pos[1], pos[2]));
    }

    /**
     * The compounds of a list tag. Earlier builds of this mod wrote lists as a
     * compound of numbered compounds, which is read the same way.
     */
    static List<NbtCompound> compounds(Object value) {
        List<NbtCompound> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof NbtCompound compound) {
                    out.add(compound);
                }
            }
        } else if (value instanceof NbtCompound compound) {
            for (Object element : compound.values()) {
                if (element instanceof NbtCompound nested) {
                    out.add(nested);
                }
            }
        }
        return out;
    }

    /** Three integers from an int array, a list of numbers or an {@code {x, y, z}} compound. */
    static int[] intTriple(Object value) {
        if (value instanceof int[] array) {
            return array.length == 3 ? array.clone() : null;
        }
        double[] doubles = doubleTriple(value);
        if (doubles == null) {
            return null;
        }
        return new int[]{(int) Math.floor(doubles[0]), (int) Math.floor(doubles[1]), (int) Math.floor(doubles[2])};
    }

    /** Three finite numbers from a list or an {@code {x, y, z}} compound. */
    static double[] doubleTriple(Object value) {
        Object x;
        Object y;
        Object z;
        if (value instanceof List<?> list && list.size() == 3) {
            x = list.get(0);
            y = list.get(1);
            z = list.get(2);
        } else if (value instanceof NbtCompound compound) {
            x = compound.get("x");
            y = compound.get("y");
            z = compound.get("z");
        } else if (value instanceof int[] array && array.length == 3) {
            return new double[]{array[0], array[1], array[2]};
        } else {
            return null;
        }
        if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
            double[] out = {nx.doubleValue(), ny.doubleValue(), nz.doubleValue()};
            for (double d : out) {
                if (!Double.isFinite(d)) {
                    return null;
                }
            }
            return out;
        }
        return null;
    }

    /**
     * A side of a schematic. The formats store sides as shorts that are read
     * unsigned; earlier builds of this mod stored ints. Anything else is 0.
     */
    static int side(NbtCompound compound, String key) {
        Object value = compound.get(key);
        if (value instanceof Short s) {
            return s & 0xFFFF;
        }
        if (value instanceof Byte b) {
            return b & 0xFF;
        }
        if (value instanceof Integer i) {
            return i;
        }
        return 0;
    }

    /**
     * Refuses a schematic larger than {@code limits.max-schematic-size} before
     * any of its blocks are allocated.
     */
    static void checkVolume(String name, long volume, long maxVolume) {
        if (maxVolume > 0 && volume > maxVolume) {
            throw new InputException("Schematic '" + name + "' holds " + volume
                    + " blocks, more than limits.max-schematic-size (" + maxVolume + ")");
        }
    }
}
