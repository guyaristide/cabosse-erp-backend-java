package com.ntech.cabosse.catalog;

import com.ntech.cabosse.catalog.dto.IndustryResponseDto;
import com.ntech.cabosse.catalog.entity.IndustryEntity;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une filière se lit dans la langue du lecteur, sans imposer de ressaisie.
 *
 * <p>Le libellé d'une filière n'est pas une valeur d'énumération qu'on
 * traduirait par une clé : c'est une donnée que la plateforme saisit au
 * back-office. L'entité porte donc un champ par langue, comme le
 * référentiel des pays.</p>
 *
 * <p>Le repli est ce qui rend le déploiement possible : les filières
 * existantes n'ont pas d'anglais, et il ne faut pas qu'elles deviennent
 * des lignes vides dans la liste des activités d'un tenant anglophone en
 * attendant que quelqu'un les complète.</p>
 */
class IndustryLocaleTest {

    private static IndustryEntity industry(String label, String labelEn) {
        IndustryEntity e = new IndustryEntity();
        e.code = "cacao";
        e.label = label;
        e.labelEn = labelEn;
        e.description = "Transformation de fèves";
        e.descriptionEn = "Bean processing";
        return e;
    }

    @Test
    void english_reader_gets_the_english_label() {
        IndustryResponseDto dto = IndustryResponseDto.from(
                industry("Cacao et chocolaterie", "Cocoa and chocolate"), Locale.ENGLISH);
        assertThat(dto.label()).isEqualTo("Cocoa and chocolate");
        assertThat(dto.description()).isEqualTo("Bean processing");
    }

    @Test
    void french_reader_gets_the_french_label() {
        IndustryResponseDto dto = IndustryResponseDto.from(
                industry("Cacao et chocolaterie", "Cocoa and chocolate"), Locale.FRENCH);
        assertThat(dto.label()).isEqualTo("Cacao et chocolaterie");
        assertThat(dto.description()).isEqualTo("Transformation de fèves");
    }

    @Test
    void a_filiere_without_english_stays_readable() {
        // Sans ce repli, la page des activités d'un tenant anglophone
        // afficherait des lignes sans nom tant que le back-office n'a pas
        // complété le catalogue.
        IndustryEntity e = industry("Hévéa et caoutchouc", null);
        e.descriptionEn = null;
        IndustryResponseDto dto = IndustryResponseDto.from(e, Locale.ENGLISH);
        assertThat(dto.label()).isEqualTo("Hévéa et caoutchouc");
        assertThat(dto.description()).isEqualTo("Transformation de fèves");
    }

    @Test
    void a_blank_english_label_counts_as_absent() {
        IndustryEntity e = industry("Manioc", "   ");
        assertThat(IndustryResponseDto.from(e, Locale.ENGLISH).label()).isEqualTo("Manioc");
    }
}
