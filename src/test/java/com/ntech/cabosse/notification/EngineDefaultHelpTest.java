package com.ntech.cabosse.notification;

import com.ntech.cabosse.notification.engine.EngineParam;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'aide qui cite une valeur par défaut se lit correctement.
 *
 * <p>{@code withDefault} compose la phrase au moment où le moteur déclare
 * ses paramètres, donc dans le contexte de la requête. Le texte obtenu est
 * rangé là où vivent les clés, et {@code help()} le repasse au catalogue :
 * comme il n'y correspond à rien, le catalogue le rend tel quel. C'est
 * correct, mais c'est une subtilité qui se casserait sans bruit si le
 * catalogue se mettait un jour à échouer sur une clé inconnue.</p>
 */
class EngineDefaultHelpTest {

    @Test
    void a_default_value_is_quoted_verbatim_inside_a_translated_sentence() {
        EngineParam param = EngineParam.optional("baseUrl", "m.ntf-p-base-url")
                .withDefault("https://api.example.com/v1");

        assertThat(param.help())
                .contains("https://api.example.com/v1")
                .doesNotContain("m.ntf-p-default-value");
        assertThat(param.label()).isNotEqualTo("m.ntf-p-base-url");
    }

    @Test
    void a_parameter_without_help_stays_without_help() {
        assertThat(EngineParam.required("host", "m.ntf-p-host").help()).isNull();
    }
}
