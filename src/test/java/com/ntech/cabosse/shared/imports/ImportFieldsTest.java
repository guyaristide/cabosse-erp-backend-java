package com.ntech.cabosse.shared.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les carnets papier mélangent trois écritures des nombres : milliers à
 * l'espace insécable (« 1 500 »), milliers à la virgule anglaise
 * (« 10,936 » pour 10 936 kg, le carnet des sorties) et décimale
 * française (« 12,5 »). Le parseur doit viser juste sur les trois.
 */
class ImportFieldsTest {

    @Test
    void comma_groups_of_three_digits_are_thousands() {
        assertThat(ImportFields.parseDecimal("10,936")).isEqualByComparingTo(new BigDecimal("10936"));
        assertThat(ImportFields.parseDecimal(" 2,594 ")).isEqualByComparingTo(new BigDecimal("2594"));
        assertThat(ImportFields.parseDecimal("1,234,567")).isEqualByComparingTo(new BigDecimal("1234567"));
    }

    @Test
    void french_decimals_and_nbsp_thousands_still_parse() {
        assertThat(ImportFields.parseDecimal("12,5")).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(ImportFields.parseDecimal("0,936")).isEqualByComparingTo(new BigDecimal("0.936"));
        assertThat(ImportFields.parseDecimal("1 500")).isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(ImportFields.parseDecimal("854 000")).isEqualByComparingTo(new BigDecimal("854000"));
        assertThat(ImportFields.parseDecimal("n/a")).isNull();
    }

    @Test
    void dates_and_text_absorb_hidden_spaces() {
        assertThat(ImportFields.parseDate(" 17/07/2026 ")).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(ImportFields.clean("KOUI IBOBE  MARCELIN ")).isEqualTo("KOUI IBOBE MARCELIN");
        assertThat(ImportFields.phoneKey("+225 01 54 53 66 88")).isEqualTo("54536688");
    }
}
