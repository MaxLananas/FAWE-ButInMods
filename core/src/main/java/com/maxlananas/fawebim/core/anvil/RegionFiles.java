package com.maxlananas.fawebim.core.anvil;

import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Read-only access to a Minecraft world's region files ({@code r.x.z.mca}).
 *
 * <p>The anvil commands use it to answer questions about chunks the server does
 * not have in memory: how many blocks the world stores, which chunks are still
 * made of air only, which biome they hold, when they were last written. Nothing
 * here writes to the world folder: the running server owns those files, so every
 * change goes through {@link com.maxlananas.fawebim.core.world.World} instead.</p>
 */
public final class RegionFiles {

    /** Sector size of the region format. */
    private static final int SECTOR = 4096;

    /** Called for every stored chunk; return false to stop the scan. */
    public interface ChunkVisitor {

        boolean visit(Chunk chunk);
    }

    /** One chunk as it is stored on disk. */
    public interface Chunk {

        int chunkX();

        int chunkZ();

        /** Epoch seconds of the last write, from the region header. */
        long modifiedSeconds();

        /** The chunk's NBT, or null when it could not be parsed. */
        NbtCompound data();
    }

    private RegionFiles() {
    }

    /** Every {@code r.x.z.mca} of a dimension directory, sorted by name. */
    public static List<Path> files(Path dimensionDirectory) {
        List<Path> files = new ArrayList<>();
        if (dimensionDirectory == null || !Files.isDirectory(dimensionDirectory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dimensionDirectory, "r.*.mca")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException e) {
            return files;
        }
        files.sort(Path::compareTo);
        return files;
    }

    /**
     * Visits every chunk stored in the given region files.
     *
     * <p>The visitor returns false to stop the scan early, which the commands use
     * to enforce the session's time and change limits.</p>
     *
     * @return the number of chunks visited
     */
    public static int forEach(List<Path> regionFiles, ChunkVisitor visitor) {
        int visited = 0;
        for (Path file : regionFiles) {
            int[] coordinates = coordinatesOf(file);
            if (coordinates == null) {
                continue;
            }
            byte[] raw;
            try {
                raw = Files.readAllBytes(file);
            } catch (IOException e) {
                continue;
            }
            for (int index = 0; index < 1024; index++) {
                int offset = readInt(raw, index * 4);
                int timestamp = readInt(raw, SECTOR + index * 4);
                int sector = offset >>> 8;
                int sectors = offset & 0xFF;
                if (sector == 0 || sectors == 0) {
                    continue;
                }
                int start = sector * SECTOR;
                if (start + 5 > raw.length) {
                    continue;
                }
                int size = readInt(raw, start);
                if (size <= 1 || start + 4 + size > raw.length) {
                    continue;
                }
                int localX = index & 31;
                int localZ = index >> 5;
                NbtCompound data = null;
                try {
                    byte[] nbt = inflate(raw, start + 5, size - 1, raw[start + 4] & 0xFF);
                    data = NbtIo.readNbtOrGzip(nbt);
                } catch (IOException | RuntimeException e) {
                    // A chunk that cannot be decoded is reported as metadata only.
                }
                visited++;
                if (!visitor.visit(new StoredChunk(coordinates[0] * 32 + localX,
                        coordinates[1] * 32 + localZ, timestamp & 0xFFFFFFFFL, data))) {
                    return visited;
                }
            }
        }
        return visited;
    }

    /** The region coordinates encoded in an {@code r.x.z.mca} file name. */
    public static int[] coordinatesOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("r.") || !name.endsWith(".mca")) {
            return null;
        }
        String[] parts = name.substring(2, name.length() - 4).split("\\.");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] inflate(byte[] raw, int start, int length, int compression) throws IOException {
        byte[] payload = new byte[length];
        System.arraycopy(raw, start, payload, 0, length);
        return switch (compression) {
            case 1 -> read(new GZIPInputStream(new ByteArrayInputStream(payload)));
            // 3 is uncompressed; 4 (LZ4) is not written by the vanilla server.
            case 3 -> payload;
            default -> read(new InflaterInputStream(new ByteArrayInputStream(payload)));
        };
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return in.readAllBytes();
        }
    }

    private static int readInt(byte[] data, int index) {
        if (index + 4 > data.length) {
            return 0;
        }
        return ByteBuffer.wrap(data, index, 4).getInt();
    }

    private record StoredChunk(int chunkX, int chunkZ, long modifiedSeconds, NbtCompound data) implements Chunk {
    }
}
