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
 * NBT reader and writer.
 *
 * <p>The classic layout is the game's: a tag byte, a name in modified UTF-8
 * (the encoding of {@link java.io.DataOutput#writeUTF}) and big-endian
 * payloads. The varint layout (varint tag types, lengths and names in
 * standard UTF-8) is only read and written for files earlier builds of this
 * mod produced.</p>
 *
 * <p>Files are not trusted: a length prefix is only honoured while the
 * document stays within the reader's memory budget, and nesting is limited to
 * {@link #MAX_DEPTH} levels, like the game's own reader. A broken or hostile
 * file ends in an {@link IOException} instead of an {@link OutOfMemoryError}
 * or a {@link StackOverflowError}.</p>
 */
public final class NbtIo {

    /** Deepest nesting of compounds and lists a document may use, as in the game. */
    public static final int MAX_DEPTH = 512;

    private NbtIo() {
    }

    /**
     * The memory a document may take when the caller gives no budget: a quarter
     * of the heap. A document bigger than that cannot be turned into anything
     * useful without putting the server at risk anyway.
     */
    public static long defaultBudget() {
        return Math.max(16L << 20, Runtime.getRuntime().maxMemory() / 4);
    }

    /** Thrown when a document would take more memory than the reader allows. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(long budget) {
            super("NBT data needs more than " + (budget >> 20) + " MiB of memory");
        }
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

    /** Reads classic NBT, gzipped or not: the gzip magic number decides. */
    public static NbtCompound readNbtOrGzip(byte[] data) throws IOException {
        return read(new ByteArrayInputStream(data), false, isGzip(data), defaultBudget());
    }

    /** True when the bytes start with the gzip magic number. */
    public static boolean isGzip(byte[] data) {
        return data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    public static NbtCompound read(byte[] data, boolean varint) throws IOException {
        return read(new ByteArrayInputStream(data), varint, false, defaultBudget());
    }

    public static NbtCompound read(InputStream stream, boolean varint, boolean gzipped) throws IOException {
        return read(stream, varint, gzipped, defaultBudget());
    }

    /**
     * Reads one document; a gzipped one is inflated as it is read, so the
     * inflated bytes are never held in memory as a whole.
     *
     * @param budget see {@link Reader#Reader(InputStream, boolean, long)}
     */
    public static NbtCompound read(InputStream stream, boolean varint, boolean gzipped, long budget)
            throws IOException {
        InputStream in = gzipped ? new GZIPInputStream(stream) : stream;
        return new Reader(new DataInputStream(new java.io.BufferedInputStream(in)), varint, budget).readRoot();
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
        write(compound, "", target, varint, gzip);
    }

    /**
     * Writes a document in the classic layout with a named root. File formats
     * are told apart by it: a Sponge v1/v2 or MCEdit schematic's root is named
     * {@code Schematic}, a Sponge v3 one's is not named.
     */
    public static void write(NbtCompound compound, String rootName, OutputStream target, boolean gzip)
            throws IOException {
        write(compound, rootName, target, false, gzip);
    }

    private static void write(NbtCompound compound, String rootName, OutputStream target, boolean varint,
                              boolean gzip) throws IOException {
        // The buffer sits in front of the deflater: every small value written
        // straight into it would be a call into zlib of its own.
        GZIPOutputStream deflater = gzip ? new GZIPOutputStream(target, 1 << 16) : null;
        DataOutputStream out = new DataOutputStream(
                new java.io.BufferedOutputStream(deflater != null ? deflater : target, 1 << 16));
        new Writer(out, varint).writeRoot(rootName, compound);
        out.flush();
        if (deflater != null) {
            deflater.finish();
        }
    }


    // ------------------------------------------------------------------ reader

    public static final class Reader {

        private final DataInputStream in;
        private final boolean varint;
        private final long budget;
        private long used;
        private int depth;

        public Reader(InputStream in, boolean varint) {
            this(in, varint, defaultBudget());
        }

        /**
         * @param budget the most memory, in estimated bytes of the objects the
         *               document becomes, that reading may allocate
         */
        public Reader(InputStream in, boolean varint, long budget) {
            this.in = in instanceof DataInputStream data ? data : new DataInputStream(in);
            this.varint = varint;
            this.budget = budget;
        }

        /**
         * Counts memory the document is about to take, before it is allocated.
         * The sizes are estimates of the objects involved, as in the game's own
         * accounting; what matters is that a length read from the file cannot
         * make the reader allocate more than the budget.
         */
        private void account(long bytes) throws IOException {
            used += bytes;
            if (bytes < 0 || used > budget) {
                throw new TooLargeException(budget);
            }
        }

        private int length() throws IOException {
            int length = varint ? readVarInt() : in.readInt();
            if (length < 0) {
                throw new IOException("Negative NBT length " + length);
            }
            return length;
        }

        private void enter() throws IOException {
            if (++depth > MAX_DEPTH) {
                throw new IOException("NBT nested deeper than " + MAX_DEPTH + " levels");
            }
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
            // At most 65535 bytes, so the string is accounted after it is read.
            String name = in.readUTF();
            account(40L + 2L * name.length());
            return name;
        }

        private String readVarString() throws IOException {
            int len = readVarInt();
            if (len < 0) {
                throw new IOException("Negative NBT length " + len);
            }
            account(40L + 2L * len);
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
            enter();
            account(48);
            NbtCompound compound = new NbtCompound();
            while (true) {
                int type = varint ? readVarInt() : in.readUnsignedByte();
                if (type == TAG_END) {
                    depth--;
                    return compound;
                }
                String name = varint ? readVarString() : readName();
                account(32);
                compound.put(name, readPayload(type));
            }
        }

        private Object readPayload(int type) throws IOException {
            return switch (type) {
                case TAG_BYTE -> in.readByte();
                case TAG_SHORT -> {
                    account(16);
                    yield in.readShort();
                }
                case TAG_INT -> {
                    account(16);
                    yield in.readInt();
                }
                case TAG_LONG -> {
                    account(24);
                    yield in.readLong();
                }
                case TAG_FLOAT -> {
                    account(16);
                    yield in.readFloat();
                }
                case TAG_DOUBLE -> {
                    account(24);
                    yield in.readDouble();
                }
                case TAG_BYTE_ARRAY -> {
                    int len = length();
                    account(24L + len);
                    byte[] arr = new byte[len];
                    in.readFully(arr);
                    yield arr;
                }
                case TAG_STRING -> varint ? readVarString() : readName();
                case TAG_LIST -> {
                    int elementType = varint ? readVarInt() : in.readUnsignedByte();
                    int len = length();
                    if (len > 0 && (elementType == TAG_END || elementType > TAG_LONG_ARRAY)) {
                        throw new IOException("NBT list of " + len + " elements of tag " + elementType);
                    }
                    enter();
                    account(40L + 8L * len);
                    List<Object> list = new ArrayList<>(len);
                    for (int i = 0; i < len; i++) {
                        list.add(readPayload(elementType));
                    }
                    depth--;
                    yield list;
                }
                case TAG_COMPOUND -> readCompoundPayload();
                case TAG_INT_ARRAY -> {
                    int len = length();
                    account(24L + 4L * len);
                    int[] arr = new int[len];
                    for (int i = 0; i < len; i++) {
                        arr[i] = in.readInt();
                    }
                    yield arr;
                }
                case TAG_LONG_ARRAY -> {
                    int len = length();
                    account(24L + 8L * len);
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
            writeRoot("", compound);
        }

        /** The varint layout has no root name, so {@code name} only counts in the classic one. */
        public void writeRoot(String name, NbtCompound compound) throws IOException {
            if (varint) {
                writeVarInt(TAG_COMPOUND);
                writeCompoundPayload(compound);
            } else {
                out.writeByte(TAG_COMPOUND);
                writeName(name);
                writeCompoundPayload(compound);
            }
        }

        public void writeCompound(NbtCompound compound) throws IOException {
            writeRoot(compound);
        }

        /**
         * Modified UTF-8, as the game reads it; a string longer than 65535 bytes
         * is refused ({@link java.io.UTFDataFormatException}) instead of having
         * its length prefix wrap around.
         */
        private void writeName(String name) throws IOException {
            out.writeUTF(name);
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
                        if (element == null || typeOf(element) != elementType) {
                            throw new IllegalArgumentException("An NBT list holds values of one type, not "
                                    + (element == null ? "null" : element.getClass().getSimpleName())
                                    + " among tag " + elementType);
                        }
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
