package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.util.InputException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The formats a schematic can be written in, each under the names FAWE looks
 * it up by, so {@code //schem save oak fast} writes what FAWE's save writes.
 *
 * <p>The format word used to be read loosely: whatever did not start with
 * {@code mcedit} or {@code structure} was written as Sponge, so a typo such as
 * {@code strucutre}, FAWE's {@code mce} or {@code schematic}, or a format the
 * mod cannot write such as {@code png} all quietly gave a {@code .schem}
 * file.</p>
 */
public enum SchematicFormat {

    SPONGE_3("sponge.3", ".schem", "fast", "fawe", "schem", "sponge", "slow", "safe"),
    SPONGE_2("sponge.2", ".schem", "fast.2", "fawe.2", "schem.2", "slow.2", "safe.2"),
    SPONGE_1("sponge.1", ".schem"),
    MCEDIT("mcedit", ".schematic", "mce", "schematic", "legacy"),
    STRUCTURE("structure", ".nbt", "nbt");

    private final String id;
    private final String suffix;
    private final List<String> aliases;

    SchematicFormat(String id, String suffix, String... aliases) {
        this.id = id;
        this.suffix = suffix;
        this.aliases = List.of(aliases);
    }

    /** The name the format is shown and stored under: sponge.3. */
    public String id() {
        return id;
    }

    /** The extension its files are written with: .schem. */
    public String suffix() {
        return suffix;
    }

    /** The other names it is looked up by, FAWE's among them. */
    public List<String> aliases() {
        return aliases;
    }

    /** The version of a Sponge format, 0 for the others. */
    public int spongeVersion() {
        return switch (this) {
            case SPONGE_3 -> 3;
            case SPONGE_2 -> 2;
            case SPONGE_1 -> 1;
            default -> 0;
        };
    }

    /** The format a name stands for, ignoring case, or null for none. */
    public static SchematicFormat find(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        for (SchematicFormat format : values()) {
            if (format.id.equals(lower) || format.aliases.contains(lower)) {
                return format;
            }
        }
        return null;
    }

    /** The format a name stands for; a name that is none is refused with the list. */
    public static SchematicFormat of(String name) {
        SchematicFormat format = find(name);
        if (format == null) {
            throw new InputException("Unknown schematic format: " + name + " (formats: "
                    + String.join(", ", ids()) + ")");
        }
        return format;
    }

    /** Every format by its id, in the order //schem formats lists them. */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (SchematicFormat format : values()) {
            ids.add(format.id);
        }
        return ids;
    }
}
