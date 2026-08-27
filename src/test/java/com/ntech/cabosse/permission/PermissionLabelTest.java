package com.ntech.cabosse.permission;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les intitulés de droits existent dans les deux langues.
 *
 * <p>Comme pour les champs d'un dossier producteur, ces clés échappent au
 * contrôle général du catalogue : il repère les clés par les appels
 * {@code Messages.msg("…")} littéraux, or celles-ci sont portées par
 * l'énumération. Et une clé absente ne lève pas, elle se renvoie
 * elle-même : l'administrateur cocherait une case intitulée
 * « m.per-stock-move », et le refus d'accès nommerait le droit manquant
 * par son identifiant.</p>
 */
class PermissionLabelTest {

    @Test
    void every_right_has_a_label_in_both_languages() {
        for (Permission permission : Permission.values()) {
            for (Locale locale : List.of(Locale.FRENCH, Locale.ENGLISH)) {
                String label = Messages.msg(locale, permission.messageKey());
                assertThat(label)
                        .as("%s en %s", permission, locale)
                        .isNotBlank()
                        .isNotEqualTo(permission.messageKey());
            }
        }
    }

    @Test
    void the_english_catalog_is_not_a_copy_of_the_french_one() {
        long identical = List.of(Permission.values()).stream()
                .filter(p -> Messages.msg(Locale.FRENCH, p.messageKey())
                        .equals(Messages.msg(Locale.ENGLISH, p.messageKey())))
                .count();
        assertThat(identical)
                .as("un lot recopié depuis le français passerait le test précédent")
                .isLessThan(Permission.values().length / 4);
    }

    @Test
    void quotes_around_a_right_follow_the_language() {
        // Les chevrons sont français. Écrits dans le code, ils partaient
        // tels quels dans une phrase anglaise.
        assertThat(Messages.msg(Locale.FRENCH, "m.per-quoted-right", "Consulter les stocks"))
                .isEqualTo("« Consulter les stocks »");
        assertThat(Messages.msg(Locale.ENGLISH, "m.per-quoted-right", "View stock"))
                .isEqualTo("\"View stock\"");
    }
}
