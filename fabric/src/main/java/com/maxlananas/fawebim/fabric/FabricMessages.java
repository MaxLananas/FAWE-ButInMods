package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Turns a {@link Msg} into a Minecraft chat component.
 *
 * <p>The engine formats its output with the legacy {@code §} colour codes (that
 * is FAWE's own format), so the adapter converts them to components with the
 * matching style. This keeps the core free of any Minecraft dependency.</p>
 */
public final class FabricMessages {

    private FabricMessages() {
    }

    public static Component component(Msg message) {
        String raw = message.raw();
        Component result = Component.empty();
        StringBuilder segment = new StringBuilder();
        Style style = Style.EMPTY;
        ChatFormatting colour = null;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < raw.length()) {
                if (segment.length() > 0) {
                    result = result.copy().append(styled(segment.toString(), style, colour,
                            bold, italic, underlined, strikethrough));
                    segment.setLength(0);
                }
                char code = Character.toLowerCase(raw.charAt(++i));
                if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                    colour = ChatFormatting.getByCode(code);
                    bold = italic = underlined = strikethrough = false;
                } else {
                    switch (code) {
                        case 'l' -> bold = true;
                        case 'm' -> strikethrough = true;
                        case 'n' -> underlined = true;
                        case 'o' -> italic = true;
                        case 'r' -> {
                            colour = null;
                            bold = italic = underlined = strikethrough = false;
                        }
                        default -> {
                            // Unknown code: keep the current style.
                        }
                    }
                }
                continue;
            }
            segment.append(c);
        }
        if (segment.length() > 0) {
            result = result.copy().append(styled(segment.toString(), style, colour,
                    bold, italic, underlined, strikethrough));
        }
        return result;
    }

    private static Component styled(String text, Style base, ChatFormatting colour,
                                    boolean bold, boolean italic, boolean underlined, boolean strikethrough) {
        Style style = base;
        if (colour != null) {
            TextColor textColour = TextColor.fromLegacyFormat(colour);
            if (textColour != null) {
                style = style.withColor(textColour);
            }
        }
        return Component.literal(text).withStyle(style
                .withBold(bold)
                .withItalic(italic)
                .withUnderlined(underlined)
                .withStrikethrough(strikethrough));
    }

    /** Maps a Minecraft {@code Direction} to the engine's. */
    public static Direction direction(net.minecraft.core.Direction direction) {
        return switch (direction) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    public static net.minecraft.core.Direction direction(Direction direction) {
        return switch (direction) {
            case UP -> net.minecraft.core.Direction.UP;
            case DOWN -> net.minecraft.core.Direction.DOWN;
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case EAST -> net.minecraft.core.Direction.EAST;
            case WEST -> net.minecraft.core.Direction.WEST;
        };
    }

    /** The item id the player is holding, e.g. {@code minecraft:wooden_axe}. */
    public static String heldItem(ServerPlayer player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            // The empty hand is an item of its own: WorldEdit binds and looks up
            // brushes and tools by the item in hand, air included.
            return "minecraft:air";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static BlockVector3 blockVector(BlockPos pos) {
        return new BlockVector3(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos blockPos(BlockVector3 vector) {
        return new BlockPos(vector.x(), vector.y(), vector.z());
    }

    public static ServerLevel level(net.minecraft.world.level.Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }
}
