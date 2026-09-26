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

    /** The banner: what this is, how to see the commands, where to ask for help. */
    public static List<Msg> lines() {
        return List.of(
                Msg.of("\u00a78\u00a7m                                                            "),
                Msg.of(Msg.title("FAWE-BIM") + " \u00a78v" + Config.VERSION
                        + " \u00a77- \u00a7fthanks for downloading the mod!"),
                Msg.of("\u00a78\u00bb \u00a77FastAsyncWorldEdit built into the game: every command runs"
                        + " in single player, with no plugin and no server."),
                Msg.of("\u00a78\u00bb \u00a77Type " + Msg.value("//help").raw() + " \u00a77for every command, "
                        + Msg.value("//help <word>").raw() + " \u00a77to search them, and "
                        + Msg.value("/fawebim").raw() + " \u00a77for the settings screen."),
                Msg.of("\u00a78\u00bb \u00a77Ideas, bug reports, updates: "
                        + Msg.value("/fawebim-discord").raw() + " \u00a77or "
                        + Msg.value(DISCORD_INVITE).raw()),
                Msg.of("\u00a78\u00a7m                                                            "));
    }

    /** The answer of {@code /fawebim-discord}. */
    public static List<Msg> discord() {
        return List.of(
                Msg.title("FAWE-BIM on Discord"),
                Msg.of("§8» §7Join the server for updates, help and bug reports:"),
                Msg.of("  " + Msg.value(DISCORD_INVITE).raw()),
                Msg.of("§8» §7Click the link, or copy it into your browser."));
    }
}
