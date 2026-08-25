package com.slyph.cloverreports.utils;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class ChatUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)&(?:#)?([0-9A-F]{6})");

    private ChatUtil() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }

        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00A7x");
            for (char character : hex.toCharArray()) {
                replacement.append('\u00A7').append(character);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public static List<String> color(List<String> input) {
        List<String> result = new ArrayList<>();
        for (String line : input) {
            result.add(color(line));
        }
        return result;
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
}
