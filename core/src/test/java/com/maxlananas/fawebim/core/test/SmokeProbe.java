package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the lines of the rcon smoke run through the engine's own dispatcher.
 *
 * <p>The smoke run needs a live server, so its rows are only checked when the
 * game is up. This probe answers the same lines as a console in a head-less
 * world of the same shape - the flat ground of the job sits far below every
 * selection the rows build in - and prints what the engine said, which is what
 * {@code scripts/smoke_lint.py} compares the expected answers against.</p>
 *
 * <p>Usage: {@code SmokeProbe <file of command lines>}</p>
 */
public final class SmokeProbe {

    /** The ground the job's flat world has, far below the rows' selections. */
    private static final int GROUND = 30;

    private SmokeProbe() {
    }

    public static void main(String[] args) throws Exception {
        BlockStateRegistry registry = new TestBlockStateRegistry();
        BlockState.setRegistry(registry);
        EditSession.BlockStateRegistryHolder.set(registry);
        CommandManager.get().initialise();
        TestWorld world = new TestWorld("smoke");
        world.fillFlat(GROUND);
        TestActor console = TestActor.positionlessConsole("Smoke", world);
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank()) {
                continue;
            }
            console.clearMessages();
            try {
                CommandManager.get().dispatch(console, line);
            } catch (Throwable failure) {
                System.out.println(line + "\t!! " + failure);
                continue;
            }
            // rcon hands the reply back as plain text, without the colour codes.
            System.out.println(line + "\t"
                    + String.join(" | ", console.messages()).replaceAll("\u00a7.", ""));
        }
    }
}
