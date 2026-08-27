package com.ntech.cabosse.members;

import com.ntech.cabosse.members.dto.MemberFileStatusDto;
import com.ntech.cabosse.members.service.MemberFileField;
import com.ntech.cabosse.shared.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les champs d'un dossier producteur se libellent dans les deux langues.
 *
 * <p>Ces clés-là échappent au contrôle général du catalogue, qui repère
 * les clés par les appels {@code Messages.msg("…")} littéraux : ici elles
 * sont portées par l'énumération. Or une clé absente n'échoue pas, elle
 * se renvoie elle-même : l'utilisateur lirait « m.mbr-field-phone » dans
 * la liste des informations à compléter, et le message qui refuse un reçu
 * d'achat afficherait le même identifiant.</p>
 */
class MemberFileFieldTest {

    @Test
    void every_field_resolves_in_both_languages() {
        for (MemberFileField field : MemberFileField.values()) {
            for (Locale locale : List.of(Locale.FRENCH, Locale.ENGLISH)) {
                String label = Messages.msg(locale, field.messageKey());
                assertThat(label)
                        .as("%s en %s", field, locale)
                        .isNotBlank()
                        .isNotEqualTo(field.messageKey());
            }
        }
    }

    @Test
    void french_and_english_labels_actually_differ() {
        // Un catalogue anglais recopié depuis le français passerait le test
        // précédent sans traduire quoi que ce soit. Village et Section
        // s'écrivent pareil dans les deux langues, le reste non.
        long identical = List.of(MemberFileField.values()).stream()
                .filter(f -> Messages.msg(Locale.FRENCH, f.messageKey())
                        .equals(Messages.msg(Locale.ENGLISH, f.messageKey())))
                .count();
        assertThat(identical)
                .as("trop d'intitulés identiques dans les deux langues")
                .isLessThan(MemberFileField.values().length / 3);
    }

    @Test
    void the_status_carries_codes_and_legacy_labels_side_by_side() {
        MemberFileStatusDto status = MemberFileStatusDto.of(
                50, List.of(MemberFileField.BIRTH_PLACE, MemberFileField.HOUSEHOLD),
                LocalDate.of(2027, 1, 1), false);

        // Les codes sont le contrat ; les intitulés restent le temps que
        // les clients basculent, en français figé pour que leur
        // comparaison éventuelle continue de tomber juste.
        assertThat(status.missingFieldCodes()).containsExactly("BIRTH_PLACE", "HOUSEHOLD");
        assertThat(status.missingFields()).containsExactly("Lieu de naissance", "Composition du ménage");
        assertThat(status.missingFieldCodes()).hasSameSizeAs(status.missingFields());
    }
}
