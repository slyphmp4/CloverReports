package com.slyph.cloverreports.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryCompatTest {

    @Test
    void isolatesCardboardFromItsIncompleteAdventureInventoryOverload() {
        assertTrue(InventoryCompat.requiresLegacyTitle("Cardboard"));
        assertTrue(InventoryCompat.requiresLegacyTitle("cardboard"));
        assertFalse(InventoryCompat.requiresLegacyTitle("Paper"));
        assertFalse(InventoryCompat.requiresLegacyTitle(null));
    }
}
