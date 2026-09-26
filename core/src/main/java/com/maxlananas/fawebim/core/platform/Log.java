package com.maxlananas.fawebim.core.platform;

/**
 * Where the engine reports what went wrong when there is no player to tell,
 * or when a player is told only that something failed.
 *
 * <p>The core has no logging dependency. The platform installs a sink that
 * forwards to its own logger - the Fabric adapter hands everything to the
 * mod's SLF4J logger - and the JDK's {@link System.Logger} takes it until
 * then, so a test or a plain JVM still sees the stack trace.</p>
 */
public final class Log {

    /** The severity of a line. */
    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    /** Receives every line the engine logs. Called from any thread. */
    @FunctionalInterface
    public interface Sink {

        void log(Level level, String message, Throwable error);
    }

    private static final Sink JDK = (level, message, error) -> {
        System.Logger logger = System.getLogger("FAWE-BIM");
        System.Logger.Level jdkLevel = switch (level) {
            case INFO -> System.Logger.Level.INFO;
            case WARN -> System.Logger.Level.WARNING;
            case ERROR -> System.Logger.Level.ERROR;
        };
        if (error == null) {
            logger.log(jdkLevel, message);
        } else {
            logger.log(jdkLevel, message, error);
        }
    };

    private static volatile Sink sink = JDK;

    private Log() {
    }

    /** Installs the platform's sink; {@code null} goes back to the JDK logger. */
    public static void install(Sink value) {
        sink = value == null ? JDK : value;
    }

    public static void info(String message) {
        sink.log(Level.INFO, message, null);
    }

    public static void warn(String message) {
        sink.log(Level.WARN, message, null);
    }

    public static void warn(String message, Throwable error) {
        sink.log(Level.WARN, message, error);
    }

    public static void error(String message, Throwable error) {
        sink.log(Level.ERROR, message, error);
    }
}
