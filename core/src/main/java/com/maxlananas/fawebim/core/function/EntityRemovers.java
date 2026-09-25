package com.maxlananas.fawebim.core.function;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The entity filters {@code /remove} matches, which WorldEdit spells
 * {@code EntityRemover}.
 *
 * <p>WorldEdit asks the platform whether an entity is a projectile, an item, a
 * falling block and so on. The engine has no platform, so the answer comes from
 * the entity type id the way vanilla registers them; a type no category claims
 * stays untouched by every filter but {@code all}.</p>
 */
public final class EntityRemovers {

    private EntityRemovers() {
    }

    /** The keywords {@code /remove} takes, and the entities each one matches. */
    public enum Type {
        ALL("all"),
        PROJECTILES("projectiles?|arrows?"),
        ITEMS("items?|drops?"),
        FALLING_BLOCKS("falling(blocks?|sand|gravel)"),
        PAINTINGS("paintings?|art"),
        ITEM_FRAMES("(item)frames?"),
        BOATS("boats?"),
        MINECARTS("minecarts?"),
        TNT("tnt"),
        XP_ORBS("xp");

        private final Pattern keyword;

        Type(String keyword) {
            this.keyword = Pattern.compile(keyword);
        }

        /** Whether the word typed on the command line names this filter. */
        public boolean matchesKeyword(String word) {
            return keyword.matcher(word).matches();
        }

        public boolean matches(String entityType) {
            return switch (this) {
                case ALL -> true;
                case PROJECTILES -> PROJECTILE_TYPES.contains(entityType);
                case ITEMS -> entityType.equals("minecraft:item");
                case FALLING_BLOCKS -> entityType.equals("minecraft:falling_block");
                case PAINTINGS -> entityType.equals("minecraft:painting");
                case ITEM_FRAMES -> entityType.equals("minecraft:item_frame")
                        || entityType.equals("minecraft:glow_item_frame");
                case BOATS -> entityType.endsWith("_boat") || entityType.endsWith("_raft")
                        || entityType.equals("minecraft:boat");
                case MINECARTS -> entityType.contains("minecart");
                case TNT -> entityType.equals("minecraft:tnt");
                case XP_ORBS -> entityType.equals("minecraft:experience_orb");
            };
        }
    }

    private static final Set<String> PROJECTILE_TYPES = Set.of(
            "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:trident", "minecraft:snowball",
            "minecraft:egg", "minecraft:ender_pearl", "minecraft:experience_bottle", "minecraft:potion",
            "minecraft:fireball", "minecraft:small_fireball", "minecraft:dragon_fireball",
            "minecraft:wither_skull", "minecraft:firework_rocket", "minecraft:llama_spit",
            "minecraft:shulker_bullet", "minecraft:fishing_bobber", "minecraft:wind_charge",
            "minecraft:breeze_wind_charge");

    /** The types named by a word, or {@code null} when no filter answers to it. */
    public static Type find(String word) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        String name = word.toLowerCase(Locale.ROOT);
        for (Type type : Type.values()) {
            if (type.matchesKeyword(name)) {
                return type;
            }
        }
        return null;
    }

    /** The types a wrong keyword may be replaced with, as upstream lists them. */
    public static String keywords() {
        return "projectiles, items, paintings, itemframes, boats, minecarts, tnt, xp, or all";
    }
}
