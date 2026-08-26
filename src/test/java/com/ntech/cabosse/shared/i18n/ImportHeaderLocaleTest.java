package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les en-têtes d'un modèle d'import suivent la langue demandée.
 *
 * <p>Un modèle n'est pas un contrat entre deux programmes : c'est un
 * fichier qu'une personne télécharge, remplit et redépose. On ne peut donc
 * pas y écrire un identifiant technique comme pour les colonnes d'export,
 * la colonne doit rester lisible. Le libellé suit donc la langue à la
 * génération, et le lecteur du fichier accepte les deux langues au
 * retour.</p>
 *
 * <p>Ce test verrouille la moitié serveur : le modèle sort bien traduit.
 * La moitié front est couverte par {@code parcelImportMapping.test.ts},
 * qui relit un fichier anglais et un fichier français.</p>
 */
class ImportHeaderLocaleTest {

    /**
     * Toutes les clés d'en-tête du catalogue, découvertes plutôt
     * qu'énumérées : une liste écrite à la main ne couvrirait que les
     * colonnes auxquelles on a pensé, et c'est l'oubli qui fait mal ici.
     */
    private static List<String> headerKeys() {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.FRENCH);
        return bundle.keySet().stream().filter(k -> k.startsWith("m.imp-h-")).sorted().toList();
    }

    @Test
    void a_template_header_follows_the_requested_language() {
        assertThat(Messages.msg(Locale.FRENCH, "m.imp-h-parcel-code")).isEqualTo("Code plantation");
        assertThat(Messages.msg(Locale.ENGLISH, "m.imp-h-parcel-code")).isEqualTo("Plantation code");
        assertThat(Messages.msg(Locale.FRENCH, "m.imp-h-parcel-surface")).isEqualTo("Superficie (ha)");
        assertThat(Messages.msg(Locale.ENGLISH, "m.imp-h-parcel-surface")).isEqualTo("Area (ha)");
    }

    @Test
    void every_header_is_translated_in_both_languages() {
        List<String> keys = headerKeys();
        assertThat(keys).as("le catalogue doit porter des en-têtes d'import").isNotEmpty();
        int identical = 0;
        for (String key : keys) {
            String fr = Messages.msg(Locale.FRENCH, key);
            String en = Messages.msg(Locale.ENGLISH, key);
            // Une clé absente se rend elle-même : ce serait un en-tête
            // « m.imp-h-… » dans le fichier livré à l'utilisateur, et le
            // fichier ne se relirait pas au retour.
            assertThat(fr).as("clé %s en français", key).isNotEqualTo(key);
            assertThat(en).as("clé %s en anglais", key).isNotEqualTo(key);
            if (fr.equals(en)) identical++;
        }

        // Un en-tête peut légitimement s'écrire pareil dans les deux langues
        // (« Grade », « Destination », « Village »), donc l'égalité n'est pas
        // une faute en soi. Une proportion élevée en serait une : elle
        // signalerait un lot recopié au lieu d'être traduit.
        assertThat(identical)
                .as("%d en-têtes sur %d identiques en FR et EN : un lot a-t-il été recopié ?",
                        identical, keys.size())
                .isLessThan(keys.size() / 4);
    }
}
