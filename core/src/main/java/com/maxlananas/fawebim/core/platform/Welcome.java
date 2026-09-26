package com.maxlananas.fawebim.core.platform;

import com.maxlananas.fawebim.core.util.Msg;

import java.util.List;

/**
 * The lines a player is handed when they join a world with the mod in it, and
 * the ones {@code /fawebim-discord} answers with.
 *
 * <p>The text lives here rather than in the adapter so that the join banner and
 * the command say exactly the same thing, and so that both are covered by the
 * engine tests.</p>
 */
public final class Welcome {

    /** The invite {@code /fawebim-discord} hands out. */
    public static final String DISCORD_INVITE = "https://discord.gg/pnJhKuU2QK";

    private Welcome() {
    }

    /**
     * One line of the banner, with what a click on it does.
     *
     * <p>The engine sends text, not packets, so the click is described here and
     * the adapter turns it into whatever its client understands: a command that
     * runs, or a link that opens. Both are optional, and a line that carries
     * neither is just read.</p>
     */
    public record Line(Msg text, String runCommand, String openUrl, String hover) {

        public static Line of(Msg text) {
            return new Line(text, null, null, null);
        }

        static Line runsCommand(Msg text, String command, String hover) {
            return new Line(text, command, null, hover);
        }

        static Line opens(Msg text, String url) {
            return new Line(text, null, url, "Open " + url);
        }
    }

    /** A rule the vanilla font draws solid, to open and close the banner. */
    private static final Msg RULE = Msg.rule(52);

    /** The banner: what this is, how to see the commands, where to ask for help. */
    public static List<Line> lines() {
        return List.of(
                Line.of(RULE),
                Line.of(Msg.result("FAWE-BIM v" + Config.VERSION, "thanks for downloading the mod!")),
                Line.of(Msg.hint("FastAsyncWorldEdit built into the game: every command runs in single player,"
                        + " with no plugin and no server.")),
                Line.runsCommand(Msg.hint("Run " + Msg.value("//help").raw() + " for every command, or "
                                + Msg.value("//help <word>").raw() + " to search them. " + Msg.value("Click here").raw()
                                + " to run it."),
                        "//help", "Run //help"),
                Line.opens(Msg.hint("Settings screen " + Msg.value("/fawebim").raw() + " - Discord "
                                + Msg.value("/fawebim-discord").raw() + " - invite " + Msg.value(DISCORD_INVITE).raw()),
                        DISCORD_INVITE),
                Line.of(RULE));
    }

    /** The answer of {@code /fawebim-discord}. */
    public static List<Msg> discord() {
        return List.of(
                Msg.title("FAWE-BIM on Discord"),
                Msg.hint("Updates, help and bug reports: the Discord server, invite below, click it or copy it:"),
                Msg.hint(Msg.value(DISCORD_INVITE).raw()),
                Msg.hint("The same invite is on the welcome banner, and in the README."));
    }
}
