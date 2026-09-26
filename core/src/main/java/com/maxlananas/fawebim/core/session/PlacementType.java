package com.maxlananas.fawebim.core.session;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;

/**
 * Where an edit starts from, which is WorldEdit's {@code PlacementType}: the
 * world origin, the block a player stands in, the first position of the
 * selection, or one of its corners.
 */
public enum PlacementType {

    WORLD("Now placing at world origin.", "Now placing at (%d, %d, %d)."),
    PLAYER("Now placing at the block you stand in.",
            "Now placing at an offset of (%d, %d, %d) from the block you stand in."),
    /**
     * The block the command was run from. WorldEdit rewrites this one into
     * {@link #WORLD} with the player's coordinates added, so it only ever
     * reaches a session through a stored session file.
     */
    HERE("Now placing at the block you stand in.",
            "Now placing at an offset of (%d, %d, %d) from the block you stand in."),
    POS1("Now placing at pos #1.", "Now placing at an offset of (%d, %d, %d) from pos #1."),
    MIN("Now placing at the minimum of the current selection.",
            "Now placing at an offset of (%d, %d, %d) from the minimum of the current selection."),
    MAX("Now placing at the maximum of the current selection.",
            "Now placing at an offset of (%d, %d, %d) from the maximum of the current selection.");

    private final String message;
    private final String offsetMessage;

    PlacementType(String message, String offsetMessage) {
        this.message = message;
        this.offsetMessage = offsetMessage;
    }

    /** Whether a source can place at this anchor: two of them need a player. */
    public boolean canBeUsedBy(Actor actor) {
        return (this != PLAYER && this != HERE) || (actor != null && actor.position() != null);
    }

    /**
     * The anchor of this type, or {@code null} when it needs a position or a
     * selection that is not there.
     */
    public BlockVector3 anchor(Actor actor, RegionSelector selector, Region selection) {
        return switch (this) {
            case WORLD -> BlockVector3.ZERO;
            case PLAYER, HERE -> actor == null ? null : actor.position();
            case POS1 -> selector == null || !selector.isDefined() ? null : selector.getPrimaryPosition();
            case MIN -> selection == null ? null : selection.getMinimumPoint();
            case MAX -> selection == null ? null : selection.getMaximumPoint();
        };
    }

    /** The line set placement prints, with the offset when there is one. */
    public String message(BlockVector3 offset) {
        if (offset == null || offset.equals(BlockVector3.ZERO)) {
            return message;
        }
        return String.format(offsetMessage, offset.x(), offset.y(), offset.z());
    }

    /** The word this type answers to on the command line, or {@code null}. */
    public static PlacementType parse(String word) {
        for (PlacementType type : values()) {
            if (type.name().equalsIgnoreCase(word)) {
                return type;
            }
        }
        return null;
    }

    /** Every name {@code /placement} accepts. */
    public static String names() {
        StringBuilder names = new StringBuilder();
        for (PlacementType type : values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(type.name().toLowerCase(java.util.Locale.ROOT));
        }
        return names.toString();
    }
}
