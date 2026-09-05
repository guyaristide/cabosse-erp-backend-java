package com.ntech.cabosse.tenant.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'une relance de migrations laisse lire ensuite.
 *
 * <p>Test unitaire pur : aucune base, aucun HTTP. Ce qui est en jeu tient
 * dans une décision, prise entrée par entrée du journal Mongock.</p>
 *
 * <p>À chaque relance, Mongock réinscrit en {@code IGNORED} tout
 * changeUnit déjà appliqué : il repasse, constate qu'il n'a rien à faire,
 * et le consigne. En ne gardant que l'entrée la plus récente, la vue
 * technique faisait alors basculer des dizaines de migrations de
 * « appliquée » à « ignorée ». Sur un tenant réel, 83 migrations
 * appliquées s'affichaient à 18 après une seule relance.</p>
 *
 * <p>L'agent qui venait de cliquer sur « relancer » lisait donc que la
 * base avait perdu ses migrations, et cliquait de nouveau. Une exécution
 * passée ne s'efface pas parce qu'on a rejoué par-dessus.</p>
 */
class MigrationHistoryAfterRerunTest {

    /** {@code decisive} est privée : la vue technique est son seul appelant. */
    private static boolean decisive(Document log) throws Exception {
        Method m = TenantTechnicalService.class.getDeclaredMethod("decisive", Document.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, log);
    }

    private static Document entry(String state) {
        return new Document("changeId", "M042_indirect_costs")
                .append("state", state)
                .append("timestamp", new Date());
    }

    @Test
    void an_execution_carries_a_verdict() throws Exception {
        assertThat(decisive(entry("EXECUTED"))).isTrue();
    }

    @Test
    void a_failure_carries_one_too_and_must_never_be_masked() throws Exception {
        // Une migration en échec qu'on rejoue sans succès doit rester
        // visible : la masquer derrière un IGNORED ferait croire la base
        // saine alors qu'elle ne l'est pas.
        assertThat(decisive(entry("FAILED"))).isTrue();
        assertThat(decisive(entry("ROLLED_BACK"))).isTrue();
        assertThat(decisive(entry("ROLLBACK_FAILED"))).isTrue();
    }

    @Test
    void an_ignored_entry_says_nothing_about_the_database() throws Exception {
        // Elle dit seulement que Mongock est repassé et n'a rien eu à
        // faire. C'est cette entrée-là qui écrasait l'histoire.
        assertThat(decisive(entry("IGNORED"))).isFalse();
    }

    @Test
    void an_unknown_state_is_not_taken_for_a_verdict() throws Exception {
        // Prudence : une version future de Mongock peut introduire un
        // état, et le prendre pour un verdict effacerait une exécution.
        assertThat(decisive(entry("SOMETHING_NEW"))).isFalse();
        assertThat(decisive(new Document("changeId", "M001"))).isFalse();
    }
}
