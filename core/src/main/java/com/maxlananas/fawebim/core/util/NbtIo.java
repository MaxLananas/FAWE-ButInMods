package com.maxlananas.fawebim.core.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * NBT reader/writer supporting both the classic (tag-prefixed) layout and the
 * "varint" layout used by Sponge Schematic v3 (compact per-value varints).
 */
public final class NbtIo {

    private NbtIo() {
    }

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    public static NbtCompound read(byte[] data) throws IOException {
        return read(data, false);
    }

    public static NbtCompound readNbtOrGzip(byte[] data) throws IOException {
        try {
            return read(ungzip(data), false);
        } catch (IOException | RuntimeException e) {
            return read(data, false);
        }
    }

    public static NbtCompound read(byte[] data, boolean varint) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return new Reader(in, varint).readRoot();
        }
    }

    public static NbtCompound read(InputStream stream, boolean varint, boolean gzipped) throws IOException {
        InputStream in = gzipped ? new GZIPInputStream(stream) : stream;
        return new Reader(new DataInputStream(in), varint).readRoot();
    }

    public static byte[] write(NbtCompound compound, boolean varint) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(compound, out, varint, false);
        return out.toByteArray();
    }

    /** Writes a compound in the layout {@link #readNbtOrGzip(byte[])} expects. */
    public static byte[] write(NbtCompound compound, boolean varint, boolean gzip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(compound, out, varint, gzip);
        return out.toByteArray();
    }

    public static void write(NbtCompound compound, OutputStream target, boolean varint, boolean gzip) throws IOException {
        OutputStream sink = gzip ? new GZIPOutputStream(target) : target;
        DataOutputStream out = new DataOutputStream(sink);
        new Writer(out, varint).writeRoot(compound);
        if (gzip) {
            ((GZIPOutputStream) sink).finish();
        }
        out.flush();
    }

    private static byte[] ungzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ reader

    public static final class Reader {

        private final DataInputStream in;
        private final boolean varint;

        public Reader(InputStream in, boolean varint) {
            this.in = new DataInputStream(in);
            this.varint = varint;
        }

        public NbtCompound readRoot() throws IOException {
            if (!varint) {
                int type = in.readUnsignedByte();
                if (type == TAG_END) {
                    return new NbtCompound();
                }
                readName();
                if (type != TAG_COMPOUND) {
                    throw new IOException("Root tag must be a compound, got " + type);
                }
                return readCompoundPayload();
            }
            // Varint layout: a varint tag type, then compound payload without a name.
            int type = readVarInt();
            if (type == TAG_END) {
                return new NbtCompound();
            }
            if (type != TAG_COMPOUND) {
                throw new IOException("Root tag must be a compound, got " + type);
            }
            return readCompoundPayload();
        }

        private String readName() throws IOException {
            int len = in.readUnsignedShort();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private String readVarString() throws IOException {
            int len = readVarInt();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public int readVarInt() throws IOException {
            int result = 0;
            int shift = 0;
            while (true) {
                byte b = in.readByte();
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 35) {
                    throw new IOException("VarInt too big");
                }
            }
        }

        public long readVarLong() throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = in.readByte();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 70) {
                    throw new IOException("VarLong too big");
                }
            }
        }

        private NbtCompound readCompoundPayload() throws IOException {
            NbtCompound compound = new NbtCompound();
            while (true) {
                int type = varint ? readVarInt() : in.readUnsignedByte();
                if (type == TAG_END) {
                    return compound;
                }
                String name = varint ? readVarString() : readName();
                compound.put(name, readPayload(type));
            }
        }

        private Object readPayload(int type) throws IOException {
            return switch (type) {
                case TAG_BYTE -> in.readByte();
                case TAG_SHORT -> in.readShort();
                case TAG_INT -> in.readInt();
                case TAG_LONG -> in.readLong();
                case TAG_FLOAT -> in.readFloat();
                case TAG_DOUBLE -> in.readDouble();
                case TAG_BYTE_ARRAY -> {
                    int len = varint ? readVarInt() : in.readInt();
                    byte[] arr = new byte[len];
                    in.readFully(arr);
                    yield arr;
                }
                case TAG_STRING -> varint ? readVarString() : readName();
                case TAG_LIST -> {
                    int elementType = varint ? readVarInt() : in.readUnsignedByte();
                    int len = varint ? readVarInt() : in.readInt();
                    List<Object> list = new ArrayList<>(Math.max(0, len));
                    for (int i = 0; i < len; i++) {
                        list.add(readPayload(elementType));
                    }
                    yield list;
                }
                case TAG_COMPOUND -> readCompoundPayload();
                case TAG_INT_ARRAY -> {
                    int len = varint ? readVarInt() : in.readInt();
                    int[] arr = new int[len];
                    for (int i = 0; i < len; i++) {
                        arr[i] = in.readInt();
                    }
                    yield arr;
                }
                case TAG_LONG_ARRAY -> {
                    int len = varint ? readVarInt() : in.readInt();
                    long[] arr = new long[len];
                    for (int i = 0; i < len; i++) {
                        arr[i] = in.readLong();
                    }
                    yield arr;
                }
                default -> throw new IOException("Unknown NBT tag " + type);
            };
        }
    }

    // ------------------------------------------------------------------ writer

    public static final class Writer {

        private final DataOutputStream out;
        private final boolean varint;

        public Writer(OutputStream out, boolean varint) {
            this.out = new DataOutputStream(out);
            this.varint = varint;
        }

        public void writeRoot(NbtCompound compound) throws IOException {
            if (varint) {
                writeVarInt(TAG_COMPOUND);
                writeCompoundPayload(compound);
            } else {
                out.writeByte(TAG_COMPOUND);
                writeName("");
                writeCompoundPayload(compound);
            }
        }

        public void writeCompound(NbtCompound compound) throws IOException {
            writeRoot(compound);
        }

        private void writeName(String name) throws IOException {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }

        private void writeVarString(String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            out.write(bytes);
        }

        public void writeVarInt(int value) throws IOException {
            while ((value & ~0x7F) != 0) {
                out.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.writeByte(value);
        }

        public void writeVarLong(long value) throws IOException {
            while ((value & ~0x7FL) != 0) {
                out.writeByte((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.writeByte((int) value);
        }

        private void writeCompoundPayload(NbtCompound compound) throws IOException {
            for (var entry : compound.entries().entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                int type = typeOf(value);
                if (varint) {
                    writeVarInt(type);
                    writeVarString(entry.getKey());
                } else {
                    out.writeByte(type);
                    writeName(entry.getKey());
                }
                writePayload(value);
            }
            if (varint) {
                writeVarInt(TAG_END);
            } else {
                out.writeByte(TAG_END);
            }
        }

        private static int typeOf(Object value) {
            if (value instanceof Byte) {
                return TAG_BYTE;
            }
            if (value instanceof Short) {
                return TAG_SHORT;
            }
            if (value instanceof Integer) {
                return TAG_INT;
            }
            if (value instanceof Long) {
                return TAG_LONG;
            }
            if (value instanceof Float) {
                return TAG_FLOAT;
            }
            if (value instanceof Double) {
                return TAG_DOUBLE;
            }
            if (value instanceof byte[]) {
                return TAG_BYTE_ARRAY;
            }
            if (value instanceof String) {
                return TAG_STRING;
            }
            if (value instanceof List<?>) {
                return TAG_LIST;
            }
            if (value instanceof NbtCompound) {
                return TAG_COMPOUND;
            }
            if (value instanceof int[]) {
                return TAG_INT_ARRAY;
            }
            if (value instanceof long[]) {
                return TAG_LONG_ARRAY;
            }
            if (value instanceof Boolean) {
                return TAG_BYTE;
            }
            throw new IllegalArgumentException("Unsupported NBT value: " + value.getClass());
        }

        private void writePayload(Object value) throws IOException {
            switch (typeOf(value)) {
                case TAG_BYTE -> out.writeByte(((Number) value).byteValue());
                case TAG_SHORT -> out.writeShort(((Number) value).shortValue());
                case TAG_INT -> out.writeInt(((Number) value).intValue());
                case TAG_LONG -> out.writeLong(((Number) value).longValue());
                case TAG_FLOAT -> out.writeFloat(((Number) value).floatValue());
                case TAG_DOUBLE -> out.writeDouble(((Number) value).doubleValue());
                case TAG_BYTE_ARRAY -> {
                    byte[] arr = (byte[]) value;
                    if (varint) {
                        writeVarInt(arr.length);
                    } else {
                        out.writeInt(arr.length);
                    }
                    out.write(arr);
                }
                case TAG_STRING -> {
                    if (varint) {
                        writeVarString((String) value);
                    } else {
                        writeName((String) value);
                    }
                }
                case TAG_LIST -> {
                    List<?> list = (List<?>) value;
                    int elementType = list.isEmpty() ? TAG_END : typeOf(list.get(0));
                    if (varint) {
                        writeVarInt(elementType);
                        writeVarInt(list.size());
                    } else {
                        out.writeByte(elementType);
                        out.writeInt(list.size());
                    }
                    for (Object element : list) {
                        writePayload(element);
                    }
                }
                case TAG_COMPOUND -> writeCompoundPayload((NbtCompound) value);
                case TAG_INT_ARRAY -> {
                    int[] arr = (int[]) value;
                    if (varint) {
                        writeVarInt(arr.length);
                    } else {
                        out.writeInt(arr.length);
                    }
                    for (int i : arr) {
                        out.writeInt(i);
                    }
                }
                case TAG_LONG_ARRAY -> {
                    long[] arr = (long[]) value;
                    if (varint) {
                        writeVarInt(arr.length);
                    } else {
                        out.writeInt(arr.length);
                    }
                    for (long l : arr) {
                        out.writeLong(l);
                    }
                }
                default -> throw new IOException("Unsupported NBT value");
            }
        }
    }

    /** Convenience: length-prefixed little-endian ints used by legacy clipboard headers. */
    public static int readVarIntSafe(DataInputStream in) throws IOException {
        try {
            return new Reader(in, true).readVarInt();
        } catch (EOFException e) {
            throw new IOException(e);
        }
    }
}
