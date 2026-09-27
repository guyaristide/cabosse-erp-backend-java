package com.ntech.cabosse.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le frein sur les tentatives de connexion (relevé le 27/09/2026).
 *
 * <p>Six essais enchaînés sur le serveur en ligne n'ont rien déclenché :
 * rien n'empêchait d'essayer un mot de passe après l'autre, et la seule
 * limite était le débit du réseau.</p>
 *
 * <p>Ce que ces tests tiennent au delà du comptage : le frein ne doit
 * pas devenir lui-même un moyen d'attaquer. Il ne dit pas si un compte
 * existe, une réussite l'efface, et il ne laisse pas une adresse
 * inventée à chaque essai faire grossir la mémoire du serveur.</p>
 */
class LoginThrottleTest {

    private static final String IP = "203.0.113.9";

    private static void fail(LoginThrottle t, String email, String ip, int times) {
        for (int i = 0; i < times; i++) t.recordFailure(email, ip);
    }

    @Test
    void laisse_passer_les_premieres_tentatives() {
        LoginThrottle t = new LoginThrottle();

        // Une faute de frappe répétée ne doit pas fermer la porte au nez
        // de quelqu'un qui travaille.
        fail(t, "awa@coop.ci", IP, LoginThrottle.MAX_ATTEMPTS - 1);

        assertThat(t.retryAfter("awa@coop.ci", IP)).isZero();
    }

    @Test
    void ferme_la_porte_au_dela_de_la_limite() {
        LoginThrottle t = new LoginThrottle();

        fail(t, "awa@coop.ci", IP, LoginThrottle.MAX_ATTEMPTS);

        Duration wait = t.retryAfter("awa@coop.ci", IP);
        assertThat(wait).isPositive();
        assertThat(wait).isLessThanOrEqualTo(LoginThrottle.LOCKOUT);
    }

    @Test
    void une_connexion_reussie_efface_les_echecs() {
        LoginThrottle t = new LoginThrottle();
        fail(t, "awa@coop.ci", IP, LoginThrottle.MAX_ATTEMPTS - 1);

        // La personne a fini par retrouver son mot de passe : la garder
        // sous surveillance la bloquerait à la prochaine faute de frappe.
        t.recordSuccess("awa@coop.ci", IP);
        fail(t, "awa@coop.ci", IP, 1);

        assertThat(t.retryAfter("awa@coop.ci", IP)).isZero();
    }

    @Test
    void changer_d_adresse_ne_contourne_pas_le_frein() {
        LoginThrottle t = new LoginThrottle();

        // Compter par e-mail seul laisserait essayer une adresse après
        // l'autre depuis la même machine.
        for (int i = 0; i < LoginThrottle.MAX_ATTEMPTS; i++) {
            fail(t, "cible" + i + "@coop.ci", IP, 1);
        }

        assertThat(t.retryAfter("encore-une-autre@coop.ci", IP)).isPositive();
    }

    @Test
    void changer_de_machine_ne_contourne_pas_le_frein_sur_un_compte() {
        LoginThrottle t = new LoginThrottle();

        // Compter par IP seule laisserait s'acharner sur un compte depuis
        // un réseau différent à chaque essai.
        for (int i = 0; i < LoginThrottle.MAX_ATTEMPTS; i++) {
            fail(t, "awa@coop.ci", "198.51.100." + i, 1);
        }

        assertThat(t.retryAfter("awa@coop.ci", "198.51.100.200")).isPositive();
    }

    @Test
    void un_compte_bloque_n_en_bloque_pas_un_autre_ailleurs() {
        LoginThrottle t = new LoginThrottle();
        fail(t, "awa@coop.ci", IP, LoginThrottle.MAX_ATTEMPTS);

        // Sinon un attaquant fermerait la porte de qui il veut, ce qui
        // ferait du frein une arme plutôt qu'une protection.
        assertThat(t.retryAfter("koffi@coop.ci", "198.51.100.7")).isZero();
    }

    @Test
    void refuse_de_la_meme_facon_un_compte_inexistant() {
        LoginThrottle t = new LoginThrottle();

        // Le frein ne connaît pas les comptes : il ne peut donc pas
        // révéler lesquels existent, ce dont la connexion se garde déjà.
        fail(t, "personne@nexiste.pas", IP, LoginThrottle.MAX_ATTEMPTS);

        assertThat(t.retryAfter("personne@nexiste.pas", IP)).isPositive();
    }

    @Test
    void ne_laisse_pas_grossir_sa_memoire_sans_fin() {
        LoginThrottle t = new LoginThrottle();

        // Une adresse inventée à chaque essai créerait une entrée à
        // chaque essai : le frein deviendrait le moyen d'épuiser la
        // mémoire du serveur.
        for (int i = 0; i < 30_000; i++) {
            t.recordFailure("jetable" + i + "@spam.test", "198.51.100." + (i % 250));
        }

        // Rien n'explose, et un compte honnête reste libre de passer.
        assertThat(t.retryAfter("awa@coop.ci", "192.0.2.1")).isZero();
    }
}
