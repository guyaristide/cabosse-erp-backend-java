package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que le serveur écrit ne dépend pas de la langue de la machine.
 *
 * <p>Le 28/08/2026, l'application déployée rendait des modèles d'import en
 * anglais à des utilisateurs francophones, alors que tout passait en
 * développement. La cause tient à une règle de {@link ResourceBundle} :
 * quand aucun catalogue ne correspond à la langue demandée, il se rabat
 * sur {@link Locale#getDefault()} <strong>avant</strong> d'atteindre le
 * catalogue de base. Le français vit dans {@code messages.properties},
 * sans suffixe ; demander le français ne trouve donc pas de
 * {@code messages_fr}, et une machine en anglais servait
 * {@code messages_en}.</p>
 *
 * <p>Le poste de développement est en français, le conteneur n'a pas de
 * langue : le défaut était invisible d'un côté et systématique de l'autre.
 * D'où ce test, qui force la langue de la machine plutôt que de la subir.
 * Il touche tout ce que le serveur écrit, pas seulement les modèles :
 * messages d'erreur, en-têtes d'export, courriels.</p>
 */
class DefaultLocaleIndependenceTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreMachineLocale() {
        Locale.setDefault(original);
        // Les catalogues sont mis en cache par langue de secours : sans
        // purge, le test suivant lirait la résolution de celui-ci.
        ResourceBundle.clearCache();
    }

    /** Force la langue de la machine, comme le fait un conteneur. */
    private void machineSpeaks(Locale locale) {
        Locale.setDefault(locale);
        ResourceBundle.clearCache();
    }

    @Test
    void french_is_served_on_an_english_machine() {
        machineSpeaks(Locale.ENGLISH);
        assertThat(Messages.msg(Locale.FRENCH, "m.imp-h-phone"))
                .as("le serveur doit rendre le français demandé, pas la langue de la machine")
                .isEqualTo("Téléphone");
    }

    @Test
    void english_is_served_on_a_french_machine() {
        machineSpeaks(Locale.FRENCH);
        assertThat(Messages.msg(Locale.ENGLISH, "m.imp-h-phone")).isEqualTo("Phone");
    }

    @Test
    void a_regional_variant_reaches_its_language() {
        machineSpeaks(Locale.ENGLISH);
        assertThat(Messages.msg(Locale.forLanguageTag("fr-CI"), "m.imp-h-phone")).isEqualTo("Téléphone");
        assertThat(Messages.msg(Locale.forLanguageTag("en-GB"), "m.imp-h-phone")).isEqualTo("Phone");
    }

    @Test
    void an_unsupported_language_falls_back_to_french_not_to_the_machine() {
        machineSpeaks(Locale.ENGLISH);
        // L'espagnol n'a pas de catalogue : le repli documenté est le
        // français, langue du produit, jamais la langue de la machine.
        assertThat(Messages.msg(Locale.forLanguageTag("es"), "m.imp-h-phone")).isEqualTo("Téléphone");
    }

    @Test
    void the_whole_catalogue_follows_the_requested_language() {
        machineSpeaks(Locale.ENGLISH);
        // Le catalogue français est lu directement à sa source, sans
        // passer par la résolution qu'on est en train de tester.
        ResourceBundle base = ResourceBundle.getBundle("messages", Locale.ROOT,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        java.util.List<String> leaked = new java.util.ArrayList<>();
        for (String key : base.keySet()) {
            // Le marqueur de devise est résolu par msg() quelle que soit la
            // langue ; ce test ne juge que la langue, on le résout donc
            // aussi sur la référence, au même repli hors requête.
            String expected = base.getString(key)
                    .replace(Messages.CURRENCY_PLACEHOLDER, CurrencyLabels.display(null));
            if (!Messages.msg(Locale.FRENCH, key).equals(expected)) {
                leaked.add(key);
            }
        }
        assertThat(leaked)
                .as("clés rendues dans une autre langue que le français demandé")
                .isEmpty();
    }
}
