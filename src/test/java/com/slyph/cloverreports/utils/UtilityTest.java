package com.slyph.cloverreports.utils;

import com.slyph.cloverreports.models.ReporterStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UtilityTest {

    @Test
    void supportsBothRequiredHexFormats() {
        assertEquals("§x§F§F§0§0§0§0Red", ChatUtil.color("&FF0000Red"));
        assertEquals("§x§F§F§0§0§0§0Red", ChatUtil.color("&#FF0000Red"));
        assertEquals(List.of("§aGreen", "§bBlue"), ChatUtil.color(List.of("&aGreen", "&bBlue")));
        assertEquals("§aGreen §lBold", ChatUtil.color("&aGreen &LBold"));
        assertEquals("Green Bold", ChatUtil.stripColor("&aGreen &lBold"));
    }

    @Test
    void neutralizesUserFormattingAndControlCharacters() {
        assertEquals("Hello ＆cRed text", ChatUtil.escapeUserText("Hello &cRed\n§ctext"));
    }

    @Test
    void validatesVanillaPlayerNames() {
        assertTrue(InputValidator.isValidPlayerName("Player_123"));
        assertFalse(InputValidator.isValidPlayerName("@a"));
        assertFalse(InputValidator.isValidPlayerName("name with space"));
        assertFalse(InputValidator.isValidPlayerName("abcdefghijklmnopq"));
    }

    @Test
    void comparesFalseReportThresholdWithoutRoundingUp() {
        ReporterStats below = new ReporterStats(159, 41);
        ReporterStats exact = new ReporterStats(4, 1);
        assertEquals(79, below.getClosedPercent());
        assertFalse(below.isClosedPercentAtLeast(80));
        assertEquals(80, exact.getClosedPercent());
        assertTrue(exact.isClosedPercentAtLeast(80));
    }
}
