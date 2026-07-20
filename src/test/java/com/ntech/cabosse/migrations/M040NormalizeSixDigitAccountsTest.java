package com.ntech.cabosse.migrations;

import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPT-16 — format 6 caractères. Verrouille deux invariants : la règle de
 * conversion {@link M040_NormalizeSixDigitAccounts#pad6} et le fait que
 * toutes les constantes du moteur de comptabilisation sont bien à 6 chiffres
 * (sinon une pièce nouvelle réintroduirait un code court).
 */
class M040NormalizeSixDigitAccountsTest {

    @Test
    void pad6_completes_short_numeric_codes_to_six() {
        assertEquals("401000", M040_NormalizeSixDigitAccounts.pad6("401"));
        assertEquals("445600", M040_NormalizeSixDigitAccounts.pad6("4456"));
        assertEquals("445660", M040_NormalizeSixDigitAccounts.pad6("44566"));
        assertEquals("310000", M040_NormalizeSixDigitAccounts.pad6("31"));
        assertEquals("530000", M040_NormalizeSixDigitAccounts.pad6("530"));
    }

    @Test
    void pad6_is_idempotent_and_leaves_non_convertible_values() {
        assertEquals("601000", M040_NormalizeSixDigitAccounts.pad6("601000"));
        assertEquals("4011000", M040_NormalizeSixDigitAccounts.pad6("4011000")); // déjà > 6
        assertEquals("", M040_NormalizeSixDigitAccounts.pad6(""));
        assertEquals("N/A", M040_NormalizeSixDigitAccounts.pad6("N/A"));
        assertNull(M040_NormalizeSixDigitAccounts.pad6(null));
    }

    @Test
    void every_engine_account_constant_is_six_digits() throws IllegalAccessException {
        for (Field f : SyscohadaAccounts.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            String value = (String) f.get(null);
            assertTrue(value.matches("\\d{6}"),
                    "Le compte " + f.getName() + " doit être à 6 chiffres mais vaut " + value);
        }
    }
}
