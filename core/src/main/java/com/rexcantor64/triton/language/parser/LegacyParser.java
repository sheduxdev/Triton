package com.rexcantor64.triton.language.parser;

import com.rexcantor64.triton.api.config.FeatureSyntax;
import com.rexcantor64.triton.api.language.Localized;
import com.rexcantor64.triton.api.language.MessageParser;
import com.rexcantor64.triton.api.language.TranslationResult;
import lombok.Getter;
import lombok.val;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextFormat;
import net.kyori.adventure.text.serializer.legacy.CharacterAndFormat;
import net.kyori.adventure.text.serializer.legacy.Reset;
import org.intellij.lang.annotations.Subst;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * A message parser that has the same behaviour as the "AdvancedComponent" parser on Triton v3 and below.
 * For backwards compatibility purposes.
 *
 * @since 4.0.0
 */
public class LegacyParser implements MessageParser {
    private static final char CLICK_DELIM = '\uE400';
    private static final char CLICK_END_DELIM = CLICK_DELIM + 1;
    private static final char HOVER_DELIM = '\uE500';
    private static final char HOVER_END_DELIM = HOVER_DELIM + 1;
    private static final char TRANSLATABLE_DELIM = '\uE600';
    private static final char KEYBIND_DELIM = '\uE700';
    private static final char FONT_START_DELIM = '\uE800';
    private static final char FONT_MID_DELIM = FONT_START_DELIM + 2;
    private static final char FONT_END_DELIM = FONT_START_DELIM + 1;

    private static final char SECTION_CHAR = '§';
    private static final char HEX_PREFIX = '#';
    private static final char HEX_CODE = 'x';

    @Override
    public @NotNull TranslationResult<String> translateString(String text, Localized language, FeatureSyntax syntax) {
        return null;
    }

    @Override
    public @NotNull TranslationResult<Component> translateComponent(Component component, Localized language, FeatureSyntax syntax) {
        return null;
    }

    /**
     * Represents a {@link Component} but as a String and with additional storage for the
     * values of click/hover events and translatable components.
     */
    @VisibleForTesting
    static class SerializedComponent {
        private final ComponentFlattener FLATTENER = ComponentFlattener.builder()
                .mapper(KeybindComponent.class, component -> KEYBIND_DELIM + component.keybind() + KEYBIND_DELIM)
                .mapper(TextComponent.class, TextComponent::content)
                .mapper(TranslatableComponent.class, component -> {
                    val uuid = UUID.randomUUID();
                    this.translatableComponents.put(uuid, component);

                    // Key is only included for backwards-compatibility
                    return TRANSLATABLE_DELIM + component.key() + TRANSLATABLE_DELIM + uuid + TRANSLATABLE_DELIM;
                })
                // ignore unknown components
                .build();

        @Getter
        private final HashMap<UUID, TranslatableComponent> translatableComponents = new HashMap<>();
        @Getter
        private final HashMap<UUID, ClickEvent> clickEvents = new HashMap<>();
        @Getter
        private final HashMap<UUID, HoverEvent<?>> hoverEvents = new HashMap<>();
        @Getter
        private String text;

        public SerializedComponent(Component component) {
            val flattenerListener = new CursedFlattenerListener();
            FLATTENER.flatten(component, flattenerListener);
            this.text = flattenerListener.toString();
        }

        /**
         * Converts this object back to an Adventure {@link Component}.
         *
         * @return The corresponding {@link Component}.
         */
        public @NotNull Component toComponent() {
            val list = toComponentList(this.text);
            if (list.isEmpty()) {
                return Component.empty();
            } else if (list.size() == 1) {
                return list.get(0);
            } else {
                return Component.text().append(list).build();
            }
        }

        private @NotNull List<@NotNull Component> toComponentList(@NotNull String text) {
            val list = new ArrayList<Component>();
            StringBuilder builder = new StringBuilder();
            TextComponent.Builder componentBuilder = Component.text();
            Style currentStyle = Style.empty(); // keep track of style of current component
            for (int i = 0; i < text.length(); i++) {
                val c = text.charAt(i);
                if (c == SECTION_CHAR) {
                    i++;
                    if (i >= text.length()) {
                        // last character in string, ignore
                        builder.append(c);
                        continue;
                    }

                    val lowercaseChar = Character.toLowerCase(text.charAt(i));
                    TextFormat format;
                    if (lowercaseChar == HEX_CODE && i + 12 < text.length()) {
                        val color = text.substring(i + 1, i + 13);
                        format = TextColor.fromHexString(HEX_PREFIX + color.replace(String.valueOf(SECTION_CHAR), ""));
                        i += 12;
                    } else {
                        format = CharacterAndFormat.defaults().stream()
                                .filter(characterAndFormat -> characterAndFormat.character() == lowercaseChar)
                                .findFirst()
                                .map(CharacterAndFormat::format)
                                .orElse(null);
                    }
                    if (format == null) {
                        // unknown code, append color code as-is
                        builder.append(c);
                        i--;
                        continue;
                    }

                    if (builder.length() != 0) {
                        // there's already some text in the builder, flush the component before changing styles
                        componentBuilder.content(builder.toString());
                        builder = new StringBuilder();
                        list.add(componentBuilder.build());
                    }
                    componentBuilder = Component.text();
                    if (format instanceof TextColor) {
                        currentStyle = Style.style((TextColor) format);
                    } else if (format instanceof TextDecoration) {
                        currentStyle = currentStyle.decorate((TextDecoration) format);
                    } else if (format instanceof Reset) {
                        currentStyle = Style.empty();
                    }
                    componentBuilder.style(currentStyle);
                } else if (c == CLICK_DELIM || c == HOVER_DELIM || c == FONT_START_DELIM) {
                    if (builder.length() != 0) {
                        // there's already some text in the builder, flush the component before changing styles
                        componentBuilder.content(builder.toString());
                        builder = new StringBuilder();
                        list.add(componentBuilder.build());
                    }
                    componentBuilder = Component.text();
                    componentBuilder.style(currentStyle);

                    // handle styles/events based on delimiter found
                    switch (c) {
                        case CLICK_DELIM:
                            // action code is still present for compability only, but not used
                            val clickUuid = UUID.fromString(text.substring(i + 2, i + 2 + 36));
                            val click = this.clickEvents.get(clickUuid);
                            componentBuilder.clickEvent(click);
                            i += 2 + 36;
                            break;
                        case HOVER_DELIM:
                            val hoverUuid = UUID.fromString(text.substring(i + 1, i + 1 + 36));
                            val hover = this.hoverEvents.get(hoverUuid);
                            componentBuilder.hoverEvent(hover);
                            i += 1 + 36;
                            break;
                        case FONT_START_DELIM:
                            i++;
                            val font = new StringBuilder();
                            while (text.charAt(i) != FONT_MID_DELIM) {
                                font.append(text.charAt(i));
                                i++;
                            }
                            i++;
                            @Subst("minecraft:default") val fontName = font.toString();
                            componentBuilder.font(Key.key(fontName));
                            break;
                    }

                    // get the content until the corresponding delimiter
                    int deep = 0;
                    StringBuilder content = new StringBuilder();
                    while (text.charAt(i) != c + 1 || deep != 0) {
                        char c1 = text.charAt(i);
                        if (c1 == c) deep++; // c == \uE400 || c == \uE500 || c == \uE800
                        if (c1 == c + 1) deep--; // c + 1 == \uE401 || c + 1 == \uE501 || c + 1 == \uE801
                        content.append(c1);
                        i++;
                    }
                    List<Component> extra = toComponentList(content.toString());
                    if (!extra.isEmpty()) {
                        componentBuilder.append(extra);
                    }
                    list.add(componentBuilder.build());
                    componentBuilder = Component.text();
                    // TODO check if this works if style changes inside the children
                    componentBuilder.style(currentStyle);
                } else if (c == TRANSLATABLE_DELIM) {
                    i++;
                    while (text.charAt(i) != TRANSLATABLE_DELIM) {
                        // ignore key (still here for backwards compatibility)
                        i++;
                    }
                    val uuid = new StringBuilder();
                    while (text.charAt(i) != TRANSLATABLE_DELIM) {
                        uuid.append(text.charAt(i));
                        i++;
                    }
                    if (builder.length() != 0) {
                        // there's already some text in the builder, flush the component before adding the component
                        componentBuilder.content(builder.toString());
                        builder = new StringBuilder();
                        list.add(componentBuilder.build());
                        componentBuilder = Component.text();
                        componentBuilder.style(currentStyle);
                    }
                    val translatableComponent = this.translatableComponents.get(UUID.fromString(uuid.toString()));
                    if (translatableComponent != null) {
                        list.add(translatableComponent.style(currentStyle));
                    }
                } else if (c == KEYBIND_DELIM) {
                    i++;
                    val key = new StringBuilder();
                    while (text.charAt(i) != KEYBIND_DELIM) {
                        key.append(text.charAt(i));
                        i++;
                    }
                    if (builder.length() != 0) {
                        // there's already some text in the builder, flush the component before adding the component
                        componentBuilder.content(builder.toString());
                        builder = new StringBuilder();
                        list.add(componentBuilder.build());
                        componentBuilder = Component.text();
                        componentBuilder.style(currentStyle);
                    }
                    val keybindComponent = Component.keybind().keybind(key.toString()).style(currentStyle).build();
                    list.add(keybindComponent);
                } else {
                    // just a normal character
                    builder.append(c);
                }
            }
            if (builder.length() != 0) {
                // flush remaining text to a new component
                componentBuilder.content(builder.toString());
                list.add(componentBuilder.build());
            }
            return list;
        }

        /**
         * Uses reserved unicode characters to delimit components/styles that cannot be represented with
         * legacy color codes, such as click, hover, translatable components, fonts, keybinds, etc.
         * The used characters are:
         * <ul>
         *     <li>\uE400 and \E401 for click events</li>
         *     <li>\uE500 and \E501 for hover events</li>
         *     <li>\uE600 for translatable components</li>
         *     <li>\uE700 for keybind components</li>
         *     <li>\uE800, \uE801 and \uE802 for fonts</li>
         * </ul>
         */
        private class CursedFlattenerListener implements FlattenerListener {
            private final StringBuilder stringBuilder = new StringBuilder();
            private Style[] stack = new Style[8];
            private int topIndex = -1;

            @Override
            public void component(@NotNull String text) {
                stringBuilder.append(text);
            }

            @Override
            public void pushStyle(@NotNull Style style) {
                val i = ++this.topIndex;
                if (i >= this.stack.length) {
                    this.stack = Arrays.copyOf(this.stack, this.stack.length * 2);
                }
                if (i > 0) {
                    style = this.stack[i - 1].merge(style);
                }
                this.stack[i] = style;

                @Nullable val color = style.color();
                if (color == null) {
                    this.stringBuilder.append(formatToString(Reset.INSTANCE));
                } else {
                    this.stringBuilder.append(formatToString(color));
                }

                style.decorations().entrySet().stream()
                        .filter(entry -> entry.getValue() == TextDecoration.State.TRUE)
                        .forEach(entry -> this.stringBuilder.append(formatToString(entry.getKey())));

                @Nullable val clickEvent = style.clickEvent();
                if (clickEvent != null && (i == 0 || !clickEvent.equals(this.stack[i - 1].clickEvent()))) {
                    val uuid = UUID.randomUUID();
                    SerializedComponent.this.clickEvents.put(uuid, clickEvent);

                    this.stringBuilder
                            .append(CLICK_DELIM)
                            .append(clickEvent.action().ordinal()) // backwards compatibility only
                            .append(uuid);
                }

                @Nullable val hoverEvent = style.hoverEvent();
                if (hoverEvent != null && (i == 0 || !hoverEvent.equals(this.stack[i - 1].hoverEvent()))) {
                    val uuid = UUID.randomUUID();
                    SerializedComponent.this.hoverEvents.put(uuid, hoverEvent);

                    this.stringBuilder
                            .append(HOVER_DELIM)
                            .append(uuid);
                }

                @Nullable val font = style.font();
                if (font != null && (i == 0 || !font.equals(this.stack[i - 1].font()))) {
                    this.stringBuilder
                            .append(FONT_START_DELIM)
                            .append(font.asString())
                            .append(FONT_MID_DELIM);
                }
            }

            @Override
            public void popStyle(@NotNull Style style) {
                val i = this.topIndex--;

                @Nullable val clickEvent = style.clickEvent();
                if (clickEvent != null && (i == 0 || !clickEvent.equals(this.stack[i - 1].clickEvent()))) {
                    this.stringBuilder.append(CLICK_END_DELIM);
                }

                @Nullable val hoverEvent = style.hoverEvent();
                if (hoverEvent != null && (i == 0 || !hoverEvent.equals(this.stack[i - 1].hoverEvent()))) {
                    this.stringBuilder.append(HOVER_END_DELIM);
                }

                @Nullable val font = style.font();
                if (font != null && (i == 0 || !font.equals(this.stack[i - 1].font()))) {
                    this.stringBuilder.append(FONT_END_DELIM);
                }
            }

            public String toString() {
                return this.stringBuilder.toString();
            }

            private @NotNull String formatToString(@NotNull TextFormat format) {
                if (format instanceof TextColor && !(format instanceof NamedTextColor)) {
                    // this is a hex color
                    final TextColor color = (TextColor) format;
                    String hexCode = String.format("%06x", color.value());
                    final StringBuilder legacy = new StringBuilder("" + SECTION_CHAR + HEX_CODE);
                    for (int i = 0, length = hexCode.length(); i < length; i++) {
                        legacy.append(SECTION_CHAR).append(hexCode.charAt(i));
                    }
                    return legacy.toString();
                }
                return CharacterAndFormat.defaults().stream()
                        .filter(characterAndFormat -> characterAndFormat.format().equals(format))
                        .findFirst()
                        .map(CharacterAndFormat::character)
                        .map(c -> "" + SECTION_CHAR + c)
                        .orElse("");
            }
        }
    }
}
