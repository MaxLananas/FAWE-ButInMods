package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.Msg;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code /cs <filename> [args]} — runs a CraftScript, WorldEdit's JavaScript
 * extension point.
 *
 * <p>A CraftScript is a {@code .js} file in the {@code craftscripts} folder that
 * WorldEdit evaluates with the JavaScript engine of the server's JVM. Modern JVMs
 * ship no engine, so this runs the script through whatever JSR-223 engine is
 * installed and says so when there is none, rather than pretending the script ran.</p>
 */
final class ScriptCommands {

    /** Scripts bind these names; they are the WorldEdit JavaScript API. */
    private static final String BINDINGS = "actor, session, world, args, commandRunner";

    private final CommandRegistry registry;

    ScriptCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/cs", "//cs");
        if (entry != null) {
            entry.description = "Execute a CraftScript";
            entry.group = "utility";
            // Upstream runs a CraftScript as the player who typed it.
            entry.requiresPlayer = true;
            entry.arguments.add("filename");
            entry.arguments.add("[args]");
            entry.handler = this::run;
        }

        CommandRegistry.Entry last = registry.registerUnlessPresent("/.s", "//.s");
        if (last == null) {
            return;
        }
        last.description = "Execute last CraftScript";
        last.group = "utility";
        last.requiresPlayer = true;
        last.arguments.add("[args]");
        last.handler = ctx -> {
            String name = ctx.session().getLastScript();
            if (name == null) {
                throw CommandRegistry.error("No script was run yet: use //cs <filename>");
            }
            ctx.actor().message(Msg.info("Running " + name));
            runNamed(ctx, name);
        };
    }

    private void runNamed(Ctx ctx, String name) {
        ScriptEngine engine = engineForPlain(name);
        if (engine == null) {
            throw CommandRegistry.error("CraftScripts need a JavaScript engine; this server has none");
        }
        engine.put("actor", ctx.actor());
        engine.put("session", ctx.session());
        engine.put("world", ctx.world());
        engine.put("args", ctx.rawArgs(0).toArray(new String[0]));
        engine.put("commandRunner", new CommandRunner(ctx));
        try {
            engine.eval(Files.readString(java.util.Objects.requireNonNull(resolve(name)), StandardCharsets.UTF_8));
        } catch (ScriptException e) {
            throw CommandRegistry.error("CraftScript '" + name + "' failed: " + e.getMessage());
        } catch (IOException e) {
            throw CommandRegistry.error("Could not read CraftScript '" + name + "'");
        }
    }

    private void run(Ctx ctx) {
        String name = ctx.arg(0);
        Path file = resolve(name);
        if (file == null) {
            throw CommandRegistry.error("No CraftScript called '" + name + "' in "
                    + Config.get().resolveDirectory(Config.get().scriptDirectory));
        }
        ScriptEngine engine = engineFor(file);
        if (engine == null) {
            throw CommandRegistry.error("CraftScripts need a JavaScript engine; this server has none. "
                    + "Add one that implements javax.script.ScriptEngine for " + BINDINGS);
        }
        engine.put("actor", ctx.actor());
        engine.put("session", ctx.session());
        engine.put("world", ctx.world());
        engine.put("args", ctx.rawArgs(1).toArray(new String[0]));
        engine.put("commandRunner", new CommandRunner(ctx));
        try {
            engine.eval(Files.readString(file, StandardCharsets.UTF_8));
        } catch (ScriptException e) {
            throw CommandRegistry.error("CraftScript '" + name + "' failed: " + e.getMessage());
        } catch (IOException e) {
            throw CommandRegistry.error("Could not read CraftScript '" + name + "'");
        }
        ctx.session().setLastScript(file.getFileName().toString());
        ctx.actor().message(Msg.result("Ran CraftScript", Msg.value(file.getFileName()).raw()));
    }

    /** {@code commandRunner.run("//set stone")} — how scripts issue commands. */
    public static final class CommandRunner {

        private final Ctx ctx;

        CommandRunner(Ctx ctx) {
            this.ctx = ctx;
        }

        public void run(String line) {
            CommandManager.get().dispatch(ctx.actor(), line);
        }

        public String actor() {
            return ctx.actor().name();
        }

        public List<String> args() {
            return ctx.rawArgs(1);
        }
    }

    private Path resolve(String name) {
        Path folder = Config.get().resolveDirectory(Config.get().scriptDirectory);
        List<String> candidates = name.toLowerCase(Locale.ROOT).endsWith(".js")
                ? List.of(name)
                : List.of(name, name + ".js");
        for (String candidate : candidates) {
            Path path = folder.resolve(candidate).normalize();
            // A script name must stay inside the folder.
            if (!path.startsWith(folder.normalize()) || !Files.isRegularFile(path)) {
                continue;
            }
            return path;
        }
        return null;
    }

    private ScriptEngine engineFor(Path file) {
        ScriptEngineManager manager = new ScriptEngineManager(ScriptCommands.class.getClassLoader());
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        ScriptEngine engine = dot < 0 ? null : manager.getEngineByExtension(name.substring(dot + 1));
        return engine != null ? engine : manager.getEngineByName("javascript");
    }

    private ScriptEngine engineForPlain(String name) {
        ScriptEngineManager manager = new ScriptEngineManager(ScriptCommands.class.getClassLoader());
        ScriptEngine engine = manager.getEngineByExtension("js");
        return engine != null ? engine : manager.getEngineByName("javascript");
    }
}
