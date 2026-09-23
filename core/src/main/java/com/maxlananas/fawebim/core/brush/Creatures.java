package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.world.EntityData;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The creature categories {@code /brush butcher} and {@code /butcher} filter on.
 *
 * <p>FAWE asks the platform for an entity's category (Bukkit's
 * {@code EntityCategory}, Sponge's entity types). The engine has no platform, so
 * the categories are read from the entity type id the way vanilla groups them,
 * and a name that is not listed falls into {@link Category#MONSTER} only when it
 * belongs to the vanilla hostile set — everything else stays untouched, so a
 * butcher can never eat an entity it does not understand.</p>
 */
public final class Creatures {

    private Creatures() {
    }

    /** One reason an entity may be butchered. */
    public enum Category {
        MONSTER,
        PETS,
        NPCS,
        GOLEMS,
        ANIMALS,
        AMBIENT,
        TAGGED,
        ARMOR_STAND,
        WATER
    }

    private static final Set<String> PETS = Set.of(
            "minecraft:wolf", "minecraft:cat", "minecraft:parrot", "minecraft:horse", "minecraft:donkey",
            "minecraft:mule", "minecraft:llama", "minecraft:trader_llama", "minecraft:camel", "minecraft:ocelot");

    private static final Set<String> NPCS = Set.of(
            "minecraft:villager", "minecraft:wandering_trader", "minecraft:zombie_villager");

    private static final Set<String> GOLEMS = Set.of(
            "minecraft:iron_golem", "minecraft:snow_golem");

    private static final Set<String> ANIMALS = Set.of(
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit",
            "minecraft:fox", "minecraft:bee", "minecraft:goat", "minecraft:panda", "minecraft:polar_bear",
            "minecraft:turtle", "minecraft:frog", "minecraft:tadpole", "minecraft:hoglin", "minecraft:strider",
            "minecraft:axolotl", "minecraft:allay", "minecraft:sniffer", "minecraft:armadillo");

    private static final Set<String> AMBIENT = Set.of(
            "minecraft:bat");

    private static final Set<String> WATER = Set.of(
            "minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish",
            "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin", "minecraft:guardian",
            "minecraft:elder_guardian", "minecraft:turtle", "minecraft:drowned");

    private static final Set<String> ARMOR_STANDS = Set.of(
            "minecraft:armor_stand");

    /** The hostile mobs a plain {@code /butcher} removes. */
    private static final Set<String> MONSTERS = Set.of(
            "minecraft:blaze", "minecraft:cave_spider", "minecraft:creeper", "minecraft:drowned", "minecraft:enderman",
            "minecraft:endermite", "minecraft:evoker", "minecraft:ghast", "minecraft:guardian", "minecraft:hoglin",
            "minecraft:husk", "minecraft:illusioner", "minecraft:magma_cube", "minecraft:phantom", "minecraft:piglin",
            "minecraft:piglin_brute", "minecraft:pillager", "minecraft:ravager", "minecraft:shulker",
            "minecraft:silverfish", "minecraft:skeleton", "minecraft:slime", "minecraft:spider",
            "minecraft:stray", "minecraft:vex", "minecraft:vindicator", "minecraft:witch", "minecraft:wither",
            "minecraft:wither_skeleton", "minecraft:zoglin", "minecraft:zombie", "minecraft:zombie_villager",
            "minecraft:zombified_piglin", "minecraft:warden", "minecraft:bogged", "minecraft:breeze",
            "minecraft:creaking", "minecraft:ender_dragon");

    /** The default filter: monsters only, which is what FAWE does without flags. */
    public static Set<Category> hostile() {
        Set<Category> categories = new LinkedHashSet<>();
        categories.add(Category.MONSTER);
        return categories;
    }

    /** The categories a set of butcher flags selects. */
    public static Set<Category> of(boolean pets, boolean npcs, boolean golems, boolean animals,
                                   boolean ambient, boolean tagged, boolean armorStands, boolean water) {
        Set<Category> categories = new LinkedHashSet<>();
        if (pets) {
            categories.add(Category.PETS);
        }
        if (npcs) {
            categories.add(Category.NPCS);
        }
        if (golems) {
            categories.add(Category.GOLEMS);
        }
        if (animals) {
            categories.add(Category.ANIMALS);
        }
        if (ambient) {
            categories.add(Category.AMBIENT);
        }
        if (tagged) {
            categories.add(Category.TAGGED);
        }
        if (armorStands) {
            categories.add(Category.ARMOR_STAND);
        }
        if (water) {
            categories.add(Category.WATER);
        }
        // A flag that names a category also allows the monsters, so -a does not
        // quietly stop killing zombies.
        if (!categories.isEmpty()) {
            categories.add(Category.MONSTER);
        }
        return categories.isEmpty() ? hostile() : categories;
    }

    /** True when the butcher may remove this entity. */
    public static boolean matches(EntityData entity, Set<Category> categories) {
        return categories.contains(categoryOf(entity));
    }

    /** The category an entity belongs to, monster when nothing else fits. */
    public static Category categoryOf(EntityData entity) {
        String type = entity.type() == null ? "" : entity.type().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            return Category.MONSTER;
        }
        if (ARMOR_STANDS.contains(type)) {
            return Category.ARMOR_STAND;
        }
        if (entity.nbt() != null && entity.nbt().contains("CustomName")) {
            return Category.TAGGED;
        }
        if (PETS.contains(type)) {
            return Category.PETS;
        }
        if (NPCS.contains(type)) {
            return Category.NPCS;
        }
        if (GOLEMS.contains(type)) {
            return Category.GOLEMS;
        }
        if (AMBIENT.contains(type)) {
            return Category.AMBIENT;
        }
        if (WATER.contains(type)) {
            return Category.WATER;
        }
        if (ANIMALS.contains(type)) {
            return Category.ANIMALS;
        }
        return Category.MONSTER;
    }

    /** True when the entity is a monster this list knows, name or not. */
    public static boolean isKnownMonster(String type) {
        return MONSTERS.contains(type == null ? "" : type.toLowerCase(Locale.ROOT));
    }
}
