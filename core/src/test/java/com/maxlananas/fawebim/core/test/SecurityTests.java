package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.Images;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.CRC32;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * What a player types cannot reach outside the folders of the mod, recurse
 * the server thread off its stack, or make it decode a picture larger than
 * its memory.
 */
final class SecurityTests {

    private SecurityTests() {
    }

    static void run() throws Exception {
        section("security");
        macrosStayInTheirFolder();
        macrosDoNotRecurseForever();
        imagesStayInTheirFolder();
        declaredSizeIsCheckedBeforeDecoding();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    /**
     * {@code /macro ../../server.properties} read any file of the server and
     * ran its lines as commands, answering "unknown command" with each of them.
     */
    private static void macrosStayInTheirFolder() {
        TestActor actor = actor("MacroPath");
        String climbing = answer(actor, "/macro ../README.md");
        check("a macro name that climbs out of the folder is refused (" + climbing + ")",
                climbing.contains("has to stay inside"));
        String absolute = answer(actor, "/macro " + Path.of("README.md").toAbsolutePath());
        check("an absolute macro path is refused", absolute.contains("has to stay inside"));
    }

    /** A macro that ran itself recursed until the stack of the server thread gave out. */
    private static void macrosDoNotRecurseForever() throws Exception {
        Path folder = Config.get().resolveDirectory(Config.get().macroDirectory);
        boolean created = !Files.exists(folder);
        Files.createDirectories(folder);
        Path loop = folder.resolve("fawebim-test-loop.txt");
        try {
            Files.writeString(loop, "/macro fawebim-test-loop.txt\n");
            TestActor actor = actor("MacroLoop");
            String answer;
            try {
                answer = answer(actor, "/macro fawebim-test-loop.txt");
            } catch (StackOverflowError error) {
                answer = "stack overflow";
            }
            check("a macro that runs itself stops at the nesting limit (" + answer.lines().findFirst().orElse("")
                    + ")", answer.contains("deep at most"));
        } finally {
            Files.deleteIfExists(loop);
            if (created) {
                try (var files = Files.walk(folder)) {
                    files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
        }
    }

    /** {@code //img /etc/...} read any image of the server's disk and told a file that exists from one that does not. */
    private static void imagesStayInTheirFolder() {
        TestActor actor = actor("ImagePath");
        check("an image name that climbs out of the folder is refused",
                answer(actor, "//img ../README.md").contains("has to stay inside"));
        check("an absolute image path is refused",
                answer(actor, "//img " + Path.of("README.md").toAbsolutePath()).contains("has to stay inside"));
        check("an image size larger than an image may cover is refused before scaling",
                answer(actor, "//img nothing.png true 100 100000,100000").contains("No image named")
                        || answer(actor, "//img nothing.png true 100 100000,100000").contains("larger than"));
        check("an image size that is not a number is a usage error",
                !answer(actor, "//img nothing.png true 100 a,b").contains("Command failed"));
    }

    /** A file of a few bytes can declare a picture of ten billion pixels; decoding it was the memory of the server. */
    private static void declaredSizeIsCheckedBeforeDecoding() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        byte[] png = bytes.toByteArray();
        // The IHDR chunk: its data starts at byte 16 with the width and the height.
        writeInt(png, 16, 100_000);
        writeInt(png, 20, 100_000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        writeInt(png, 29, (int) crc.getValue());
        boolean refused;
        try {
            Images.decode(new ByteArrayInputStream(png), Images.MAX_DECODED_PIXELS);
            refused = false;
        } catch (Images.TooLargeException e) {
            refused = true;
        }
        check("an image declaring 100000x100000 pixels is refused from its header", refused);

        ByteArrayOutputStream small = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", small);
        BufferedImage read = Images.decode(new ByteArrayInputStream(small.toByteArray()), Images.MAX_DECODED_PIXELS);
        check("a small image still decodes", read != null && read.getWidth() == 3 && read.getHeight() == 2);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
