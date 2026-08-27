package com.ntech.cabosse.notification;

import com.ntech.cabosse.notification.engine.EngineParam;
import com.ntech.cabosse.notification.engine.ProviderEnginePort;
import com.ntech.cabosse.shared.i18n.Messages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les champs de configuration d'un fournisseur se lisent dans les deux
 * langues.
 *
 * <p>L'écran se dessine tout seul depuis ce que chaque moteur déclare, et
 * il est servi à l'administrateur d'un tenant, qui n'est pas forcément
 * francophone. Ces libellés portent donc des clés, et comme partout où une
 * clé vit ailleurs que dans un appel {@code Messages.msg("…")} littéral,
 * le contrôle général du catalogue ne les voit pas : une clé absente
 * afficherait son propre identifiant en face du champ.</p>
 */
@QuarkusTest
class EngineParamLabelTest {

    @Inject Instance<ProviderEnginePort> engines;

    @Test
    void every_declared_parameter_reads_in_both_languages() {
        List<ProviderEnginePort> all = engines.stream().toList();
        assertThat(all).as("aucun moteur trouvé").isNotEmpty();

        for (ProviderEnginePort engine : all) {
            for (EngineParam param : engine.declaredParams()) {
                for (Locale locale : List.of(Locale.FRENCH, Locale.ENGLISH)) {
                    String label = Messages.msg(locale, param.labelKey());
                    assertThat(label)
                            .as("%s / %s en %s", engine.code(), param.code(), locale)
                            .isNotBlank()
                            .isNotEqualTo(param.labelKey());

                    // L'aide qui cite une valeur par défaut est composée à la
                    // volée : elle porte déjà du texte, pas une clé.
                    if (param.helpKey() != null && param.helpKey().startsWith("m.")) {
                        assertThat(Messages.msg(locale, param.helpKey()))
                                .as("aide de %s / %s en %s", engine.code(), param.code(), locale)
                                .isNotEqualTo(param.helpKey());
                    }
                }
            }
        }
    }
}
