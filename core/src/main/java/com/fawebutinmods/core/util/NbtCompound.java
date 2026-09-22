package com.fawebutinmods.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dependency-free NBT compound tag.
 *
 * <p>Values are stored as plain Java objects:</p>
 * <ul>
 *   <li>{@code Byte, Short, Integer, Long, Float, Double, String}</li>
 *   <li>{@code byte[], int[], long[]}</li>
 *   <li>{@code List<Object>} for lists</li>
 *   <li>{@link NbtCompound} for nested compounds</li>
 * </ul>
 *
 * <p>The engine needs NBT for schematics (v1/v2/v3, legacy MCEdit, structure
 * files), entity data and block entity payloads.</p>
 */
public class NbtCompound implements Cloneable {

    private final Map<String, Object> values = new LinkedHashMap<>();

    public NbtCompound() {
    }

    // ------------------------------------------------------------------ writes

    public NbtCompound put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public NbtCompound putByte(String key, int value) {
        return put(key, (byte) value);
    }

    public NbtCompound putShort(String key, int value) {
        return put(key, (short) value);
    }

    public NbtCompound putInt(String key, int value) {
        return put(key, value);
    }

    public NbtCompound putLong(String key, long value) {
        return put(key, value);
    }

    public NbtCompound putFloat(String key, float value) {
        return put(key, value);
    }

    public NbtCompound putDouble(String key, double value) {
        return put(key, value);
    }

    public NbtCompound putString(String key, String value) {
        return put(key, value == null ? "" : value);
    }

    public NbtCompound putBoolean(String key, boolean value) {
        return put(key, (byte) (value ? 1 : 0));
    }

    public NbtCompound putCompound(String key, NbtCompound value) {
        return put(key, value);
    }

    public NbtCompound putList(String key, List<?> value) {
        return put(key, new ArrayList<>(value));
    }

    public NbtCompound putIntArray(String key, int[] value) {
        return put(key, value);
    }

    public NbtCompound putByteArray(String key, byte[] value) {
        return put(key, value);
    }

    public NbtCompound putLongArray(String key, long[] value) {
        return put(key, value);
    }

    public Object remove(String key) {
        return values.remove(key);
    }

    // ------------------------------------------------------------------- reads

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public Set<String> keySet() {
        return values.keySet();
    }

    public Map<String, Object> entries() {
        return values;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public Object getOrDefault(String key, Object fallback) {
        Object v = values.get(key);
        return v == null ? fallback : v;
    }

    public byte getByte(String key, int fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.byteValue() : (byte) fallback;
    }

    public int getInt(String key, int fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public short getShort(String key, int fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.shortValue() : (short) fallback;
    }

    public long getLong(String key, long fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    public float getFloat(String key, float fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    public double getDouble(String key, double fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.byteValue() != 0 : fallback;
    }

    public String getString(String key, String fallback) {
        Object v = values.get(key);
        return v instanceof String s ? s : fallback;
    }

    public NbtCompound getCompound(String key) {
        Object v = values.get(key);
        if (v instanceof NbtCompound c) {
            return c;
        }
        NbtCompound created = new NbtCompound();
        values.put(key, created);
        return created;
    }

    public NbtCompound getCompoundOrNull(String key) {
        Object v = values.get(key);
        return v instanceof NbtCompound c ? c : null;
    }

    public List<NbtCompound> getCompoundList(String key) {
        List<NbtCompound> out = new ArrayList<>();
        Object v = values.get(key);
        if (v instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof NbtCompound c) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        Object v = values.get(key);
        if (v instanceof List<?> list) {
            return (List<Object>) list;
        }
        List<Object> created = new ArrayList<>();
        values.put(key, created);
        return created;
    }

    public int[] getIntArray(String key) {
        Object v = values.get(key);
        return v instanceof int[] arr ? arr : new int[0];
    }

    public byte[] getByteArray(String key) {
        Object v = values.get(key);
        return v instanceof byte[] arr ? arr : new byte[0];
    }

    public long[] getLongArray(String key) {
        Object v = values.get(key);
        return v instanceof long[] arr ? arr : new long[0];
    }

    /** Deep merge of another compound (used for schematic metadata defaults). */
    public void merge(NbtCompound other) {
        for (Map.Entry<String, Object> entry : other.values.entrySet()) {
            Object existing = values.get(entry.getKey());
            if (existing instanceof NbtCompound a && entry.getValue() instanceof NbtCompound b) {
                a.merge(b);
            } else {
                values.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public Collection<Object> values() {
        return values.values();
    }

    @Override
    public NbtCompound clone() {
        NbtCompound copy = new NbtCompound();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            copy.values.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof NbtCompound c) {
            return c.clone();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(copyValue(o));
            }
            return out;
        }
        if (value instanceof byte[] a) {
            return a.clone();
        }
        if (value instanceof int[] a) {
            return a.clone();
        }
        if (value instanceof long[] a) {
            return a.clone();
        }
        return value;
    }

    /** Serialises into the given NBT writer. */
    public void write(NbtIo.Writer writer) throws java.io.IOException {
        writer.writeCompound(this);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
