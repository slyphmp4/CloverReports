package com.slyph.cloverreports.utils;

import java.util.regex.Pattern;

public final class InputValidator {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private InputValidator() {
    }

    public static boolean isValidPlayerName(String value) {
        return value != null && PLAYER_NAME.matcher(value).matches();
    }

    public static boolean isSingleLine(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
