package com.ntech.cabosse.shared.i18n;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Catalogue des messages métier (exceptions, avertissements d'import),
 * miroir du mécanisme Jakarta des messages de validation.
 *
 * <p>Les textes vivent dans {@code messages.properties} (français, défaut)
 * et {@code messages_en.properties} ; la langue est celle de la requête en
 * cours ({@link RequestLocale}), français hors requête. Une clé absente
 * est renvoyée telle quelle : visible, donc corrigée vite, et couverte par
 * le test de parité des catalogues.</p>
 *
 * <p>Les paramètres passent par {@link MessageFormat} : dans un message
 * paramétré, l'apostrophe s'écrit doublée ({@code ''}) dans le catalogue.
 * Un message sans paramètre est rendu tel quel, apostrophes simples
 * comprises.</p>
 *
 * <p><strong>Hors requête, la langue ne se devine pas.</strong> Une tâche
 * planifiée, un consommateur d'événement ou une migration n'ont aucune
 * requête sous la main : {@link #current()} y répond français, et c'est le
 * bon défaut tant que personne ne prétend le contraire. Un message destiné
 * à quelqu'un doit donc être rendu avec {@link #msg(Locale, String,
 * Object...)} en passant la langue de son destinataire, décidée au moment
 * où l'opération est demandée et transportée avec elle. Voir
 * {@link Locales#of(String)} pour convertir une préférence stockée.</p>
 */
public final class Messages {

    private Messages() {}

    /** Rend dans la langue de la requête en cours. */
    public static String msg(String key, Object... args) {
        return msg(current(), key, args);
    }

    /**
     * Rend dans une langue donnée. À utiliser dès que le destinataire n'est
     * pas l'auteur de la requête en cours : notification différée, courriel
     * déclenché par un événement, document produit pour un tiers.
     */
    public static String msg(Locale locale, String key, Object... args) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);
        String template = bundle.containsKey(key) ? bundle.getString(key) : key;
        return args.length == 0 ? template : MessageFormat.format(template, args);
    }

    /** Langue de la requête en cours, français hors contexte de requête. */
    public static Locale current() {
        try {
            ArcContainer container = Arc.container();
            if (container != null && container.requestContext().isActive()) {
                return container.instance(RequestLocale.class).get().locale();
            }
        } catch (RuntimeException ignored) {
            // Contexte indisponible (démarrage, tâche de fond) : français.
        }
        return Locale.FRENCH;
    }
}
