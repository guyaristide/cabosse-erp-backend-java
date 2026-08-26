package com.ntech.cabosse.shared.i18n;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.List;
import java.util.Locale;

/**
 * Résout la langue de la requête depuis {@code Accept-Language}.
 *
 * <p>Seuls le français (défaut) et l'anglais sont servis ; toute autre
 * préférence retombe sur le français plutôt que d'exposer des clés
 * brutes. Le front envoie la langue de l'interface sur chaque appel.</p>
 */
public class LocaleFilter {

    @Inject RequestLocale requestLocale;

    @ServerRequestFilter
    public void resolve(ContainerRequestContext ctx) {
        List<Locale> wanted = ctx.getAcceptableLanguages();
        for (Locale locale : wanted) {
            String lang = locale.getLanguage();
            if (lang.equals("fr")) return; // défaut déjà en place
            if (lang.equals("en")) {
                requestLocale.set(Locale.ENGLISH);
                return;
            }
        }
    }
}
