package com.slyph.cloverreports.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)&(?:#)?([0-9A-F]{6})");
    private static final String LEGACY_CODES = "0123456789abcdefklmnorx";
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private ChatUtil() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }

        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00A7x");
            for (char character : hex.toCharArray()) {
                replacement.append('\u00A7').append(character);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return translateLegacyCodes(buffer.toString());
    }

    public static List<String> color(List<String> input) {
        List<String> result = new ArrayList<>(input.size());
        for (String line : input) {
            result.add(color(line));
        }
        return result;
    }

    public static Component component(String input) {
        return LEGACY_SERIALIZER.deserialize(color(input));
    }

    public static List<Component> components(List<String> input) {
        List<Component> result = new ArrayList<>(input.size());
        for (String line : input) {
            result.add(component(line));
        }
        return result;
    }

    public static Component itemComponent(String input) {
        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(component(input));
    }

    public static List<Component> itemComponents(List<String> input) {
        List<Component> result = new ArrayList<>(input.size());
        for (String line : input) {
            result.add(itemComponent(line));
        }
        return result;
    }

    public static String stripColor(String input) {
        return PlainTextComponentSerializer.plainText().serialize(component(input));
    }

    public static String escapeUserText(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '\u00A7') {
                if (index + 1 < input.length()) {
                    index++;
                }
                continue;
            }
            if (character == '&') {
                result.append('\uFF06');
                continue;
            }
            if (Character.isISOControl(character)) {
                result.append(' ');
                continue;
            }
            result.append(character);
        }
        return result.toString();
    }

    private static String translateLegacyCodes(String input) {
        char[] characters = input.toCharArray();
        for (int index = 0; index + 1 < characters.length; index++) {
            if (characters[index] == '&' && LEGACY_CODES.indexOf(Character.toLowerCase(characters[index + 1])) >= 0) {
                characters[index] = '\u00A7';
                characters[index + 1] = Character.toLowerCase(characters[index + 1]);
            }
        }
        return new String(characters);
    }
}
