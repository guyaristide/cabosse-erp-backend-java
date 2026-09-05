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
 * <p><strong>Un nombre passé en paramètre est mis en forme selon la
 * langue.</strong> C'est voulu pour une grandeur : un montant sort
 * « 150 000 » en français et « 150,000 » en anglais, mieux que le
 * « 150000 » brut de la concaténation qu'on remplace. Ça ne l'est pas pour
 * un identifiant : un numéro de ligne deviendrait « 1 234 ». Passer donc
 * les numéros, identifiants et références en {@link String#valueOf},
 * jamais les montants ni les quantités.</p>
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

    /**
     * Résolution sans repli sur la langue de la machine.
     *
     * <p>Sans cela, {@link ResourceBundle#getBundle} se rabat sur
     * {@link Locale#getDefault()} avant d'atteindre le catalogue de base.
     * Le français vit dans {@code messages.properties}, sans suffixe :
     * demander le français ne trouve donc pas de {@code messages_fr}, et
     * une machine dont la langue est l'anglais servait
     * {@code messages_en} — du français demandé, de l'anglais rendu.</p>
     *
     * <p>Le défaut était invisible en développement sur un poste en
     * français, et systématique en production, où le conteneur n'a pas de
     * langue. Il touchait tout ce que le serveur écrit : messages
     * d'erreur, en-têtes d'export, modèles d'import.</p>
     */
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

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
        ResourceBundle bundle = ResourceBundle.getBundle("messages", locale, NO_FALLBACK);
        String template = bundle.containsKey(key) ? bundle.getString(key) : key;
        // La devise se résout avant MessageFormat : un {currency} qui lui
        // parviendrait le ferait échouer, l'index n'étant pas numérique.
        if (template.contains(CURRENCY_PLACEHOLDER)) {
            template = template.replace(CURRENCY_PLACEHOLDER, currentCurrencyLabel());
        }
        return args.length == 0 ? template : MessageFormat.format(template, args);
    }

    /**
     * Marqueur de devise dans les catalogues : {@code {currency}}.
     *
     * <p>La devise ne s'écrit jamais en dur dans un message (règle de la
     * maison, 04/09/2026). Un en-tête d'export s'écrit
     * {@code Montant ({currency})} et rend « Montant (FCFA) » pour un
     * tenant en XOF, « Montant (GHS) » pour un tenant au cedi.</p>
     */
    public static final String CURRENCY_PLACEHOLDER = "{currency}";

    /**
     * Devise du tenant de la requête en cours, FCFA hors requête.
     *
     * <p>Pour les textes assemblés hors catalogue, descriptions d'audit en
     * tête, qui citent un montant et doivent nommer la devise du tenant.</p>
     */
    public static String currencyLabel() {
        return currentCurrencyLabel();
    }

    private static String currentCurrencyLabel() {
        try {
            ArcContainer container = Arc.container();
            if (container != null && container.requestContext().isActive()) {
                return CurrencyLabels.display(container
                        .instance(com.ntech.cabosse.shared.tenant.TenantContext.class)
                        .get().currency());
            }
        } catch (RuntimeException ignored) {
            // Contexte indisponible (démarrage, tâche de fond) : défaut.
        }
        return CurrencyLabels.display(null);
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
