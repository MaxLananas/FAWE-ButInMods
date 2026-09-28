package com.maxlananas.fawebim.core.actor;

import com.maxlananas.fawebim.core.util.Msg;

/**
 * An actor that swallows everything printed to it.
 *
 * <p>FAWE's {@code -h} switches ("hide any printed output") and the quiet form of
 * the command brushes keep a command that runs on every brush click from
 * flooding the chat. Everything else — position, selection, session, world — is
 * forwarded, so the wrapped command still does its work.</p>
 */
public final class SilentActor extends DelegatingActor {

    public SilentActor(Actor delegate) {
        super(delegate);
    }

    @Override
    public void message(Msg message) {
        // Deliberately dropped.
    }

    @Override
    public void link(String text, String url) {
    }

    @Override
    public void commandLink(String text, String commandRun, String hover) {
    }

    @Override
    public void suggestLink(String text, String command, String hover) {
    }

    @Override
    public void status(Msg message) {
    }

    @Override
    public boolean openConfigurationScreen() {
        return false;
    }
}
