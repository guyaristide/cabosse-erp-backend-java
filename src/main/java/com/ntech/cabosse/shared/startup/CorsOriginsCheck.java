package com.ntech.cabosse.shared.startup;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Vérifie au démarrage que la liste des origines autorisées désigne un
 * site (relevé le 28/09/2026).
 *
 * <p>Toutes les API ont répondu 403 après un déploiement. La variable qui
 * porte le domaine était définie vide : tant qu'elle était seule dans la
 * liste, la liste était vide, et une liste vide ne restreint rien, ce qui
 * était déjà le défaut de sécurité relevé la veille. Y ajouter les postes
 * de développement l'a rendue non vide, donc appliquée, et l'adresse du
 * site n'y figurait nulle part.</p>
 *
 * <p>Les deux états sont mauvais et aucun ne se voyait : dans un cas
 * n'importe quelle origine passe, dans l'autre aucune. Le contrôle porte
 * donc sur ce qui distingue une liste utile d'une liste inutile :
 * <strong>une origine au moins qui ne soit pas un poste local</strong>.
 * Les expressions pour {@code localhost} servent l'outillage, jamais le
 * site ; une liste qui n'a qu'elles ne sert personne.</p>
 *
 * <p>Le démarrage échoue plutôt que de laisser l'application répondre. Un
 * serveur qui démarre en refusant toutes ses origines se signale comme
 * déployé, et la panne ne se découvre qu'à l'usage. Ici le déploiement
 * s'arrête et dit quoi poser.</p>
 *
 * <p>Sans objet en développement et en test, où l'on ne déploie rien.</p>
 */
@ApplicationScoped
public class CorsOriginsCheck {

    /**
     * Valeur à poser pour une installation délibérément sans site : une
     * API consommée de serveur à serveur n'a pas d'origine à autoriser.
     * Nommer ce cas évite de le confondre avec un oubli.
     */
    static final String NO_SITE = "none";

    @ConfigProperty(name = "quarkus.http.cors.origins")
    Optional<List<String>> origins;

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent ev) {
        if (!ConfigUtils.isProfileActive("prod") && !ConfigUtils.isProfileActive("qa")) return;
        if (!Boolean.TRUE.equals(enabled())) return;

        List<String> declared = origins.orElse(List.of()).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        if (declared.size() == 1 && NO_SITE.equalsIgnoreCase(declared.get(0))) {
            log.warn("CORS : aucune origine web déclarée, installation sans site");
            return;
        }
        if (!designatesASite(declared)) {
            throw new IllegalStateException(
                    "CORS : la liste des origines ne désigne aucun site. Poser CORS_ORIGINS"
                            + " sur l'adresse servie au public (par exemple"
                            + " https://exemple.com, plusieurs séparées par des virgules), ou"
                            + " la valeur « " + NO_SITE + " » pour une API sans site."
                            + " Origines lues : " + declared);
        }
        log.infof("CORS : %d origine(s) autorisée(s)", declared.size());
    }

    /**
     * La décision, isolée de la lecture de configuration pour être
     * éprouvée directement : c'est elle qui sépare une liste utile d'une
     * liste que personne ne peut employer.
     */
    static boolean designatesASite(List<String> declared) {
        return declared.stream().map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(CorsOriginsCheck::namesASite);
    }

    /** Le drapeau se lit à part : absent, CORS ne s'applique pas du tout. */
    private Boolean enabled() {
        return org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.cors.enabled", Boolean.class).orElse(false);
    }

    /**
     * Une entrée qui vaut pour un site, par opposition à un poste de
     * développement. Les expressions sont reconnues à leurs barres, et
     * celles qui ne parlent que de la machine locale ne comptent pas.
     */
    private static boolean namesASite(String entry) {
        // Les antislashs tombent d'abord : une expression échappe ses
        // points, et « 127\\.0\\.0\\.1 » passerait pour un domaine.
        String e = entry.toLowerCase().replace("\\", "");
        return !e.contains("localhost") && !e.contains("127.0.0.1") && !e.contains("[::1]");
    }
}
