package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.util.Theme;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Every line the commands wrote during the run has the theme's look: an
 * answer opens with the name in the gradient and the marker, a line under it
 * is indented, and no colour outside the theme's palette appears. A command
 * that formats a line of its own breaks this, which is the point.
 */
final class MessageStyleTests {

    private MessageStyleTests() {
    }

    static void run() {
        section("message style");
        builders();
        everyLineOfTheRun();
    }

    private static void builders() {
        String tag = Theme.TAG + " " + Theme.MARKER + " ";
        checkEquals("a result reads name, marker, label, detail", tag + "Copied: 12 block(s)",
                Msg.result("Copied", "12 block(s)").plain());
        check("an error line is red after the marker",
                Msg.error("No clipboard").raw().contains(Theme.ERROR + Theme.MARKER + " " + Theme.ERROR));
        check("a success line is green after the marker",
                Msg.success("Done").raw().contains(Theme.SUCCESS + Theme.MARKER + " " + Theme.SUCCESS));
        check("a warning is gold after the marker",
                Msg.warn("Careful").raw().contains(Theme.WARNING + Theme.MARKER + " " + Theme.WARNING));
        checkEquals("a line under a heading is indented", "  - stone: 12", Msg.item("stone", "12").plain());
        checkEquals("a value hands the line its colour back",
                Theme.TEXT + "at " + Theme.NUMBER + "5" + Theme.TEXT + " now",
                stripPrefix(Msg.info("at " + Msg.value(5).raw() + " now").raw()));
        check("a count in a sentence ends in the sentence's colour",
                Msg.error("only " + Msg.count(3) + " left").raw().endsWith(Theme.ERROR + " left"));
    }

    /** What follows the name and the marker of an answer, as it was coloured. */
    private static String stripPrefix(String raw) {
        int marker = raw.indexOf(Theme.MARKER + " ");
        return raw.substring(marker + Theme.MARKER.length() + 1);
    }

    private static void everyLineOfTheRun() {
        String tag = Theme.TAG + " " + Theme.MARKER + " ";
        List<String> stray = new ArrayList<>();
        Set<String> foreign = new LinkedHashSet<>();
        Set<Character> palette = new java.util.HashSet<>();
        for (String role : new String[] {Theme.TEXT, Theme.STRONG, Theme.MUTED, Theme.NUMBER, Theme.FLAG,
                Theme.NAME, Theme.SUCCESS, Theme.WARNING, Theme.ERROR}) {
            palette.add(role.charAt(1));
        }
        // Bold for a section, struck-through spaces for a rule, the reset a
        // fragment may end a line with, and the hex form of the gradient.
        palette.add('l');
        palette.add('m');
        palette.add('r');
        int lines = 0;
        for (String raw : TestActor.receivedMessages()) {
            String plain = plain(raw);
            if (plain.isBlank()) {
                continue;
            }
            lines++;
            boolean answer = plain.startsWith(tag) && raw.startsWith("\u00a7x");
            boolean under = plain.startsWith("  ");
            if (!answer && !under && !isRule(raw)) {
                stray.add(plain);
            }
            for (int i = 0; i + 1 < raw.length(); i++) {
                if (raw.charAt(i) != '\u00a7') {
                    continue;
                }
                char code = Character.toLowerCase(raw.charAt(i + 1));
                if (code == 'x') {
                    i += 13;
                    continue;
                }
                if (!palette.contains(code)) {
                    foreign.add("\u00a7" + code + " in: " + plain);
                }
                i++;
            }
        }
        System.out.println("    lines checked: " + lines);
        check("the run wrote lines", lines > 1000);
        check("every line is an answer or a line under one (" + stray.size() + " stray)", stray.isEmpty());
        for (String line : stray.subList(0, Math.min(10, stray.size()))) {
            System.out.println("      stray: " + line);
        }
        check("no colour outside the theme's palette (" + foreign.size() + ")", foreign.isEmpty());
        for (String line : new ArrayList<>(foreign).subList(0, Math.min(10, foreign.size()))) {
            System.out.println("      foreign: " + line);
        }
    }

    private static boolean isRule(String raw) {
        return raw.startsWith(Theme.MUTED + "\u00a7m");
    }

    private static String plain(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\u00a7' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            out.append(raw.charAt(i));
        }
        return out.toString();
    }
}
