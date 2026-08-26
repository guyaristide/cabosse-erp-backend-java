package com.ntech.cabosse.shared.i18n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Textes d'un courriel, rendus dans la langue de son destinataire.
 *
 * <p>Les gabarits d'e-mail sont presque entièrement faits de style en
 * ligne : la messagerie n'accepte rien d'autre. Les dupliquer par langue
 * dupliquerait donc surtout de la mise en page, et une retouche de
 * présentation devrait être répétée dans chaque copie. Le texte est sorti
 * du gabarit à la place : le gabarit garde sa mise en page, unique, et
 * reçoit ses phrases déjà rendues.</p>
 *
 * <p>Elles viennent du catalogue déjà en place ({@code messages.properties}
 * et son miroir anglais), et non d'un second catalogue propre aux
 * courriels : deux catalogues, ce serait deux endroits où traduire, deux
 * tests de parité, et un jour l'un des deux oublié.</p>
 *
 * <p>Usage : {@code MailTexts.in(locale).put("greeting",
 * "m.mail-user-invitation-greeting", prenom)}, puis
 * {@code template.data("t", textes.build())} et {@code {t.greeting}} dans
 * le gabarit.</p>
 */
public final class MailTexts {

    private final Locale locale;
    private final Map<String, String> values = new LinkedHashMap<>();

    private MailTexts(Locale locale) {
        this.locale = locale;
    }

    public static MailTexts in(Locale locale) {
        return new MailTexts(locale != null ? locale : Locale.FRENCH);
    }

    /**
     * @param name nom lu dans le gabarit ({@code {t.<name>}})
     * @param key  clé du catalogue
     * @param args paramètres du message, dans l'ordre des {@code {0}, {1}…}
     */
    public MailTexts put(String name, String key, Object... args) {
        values.put(name, Messages.msg(locale, key, args));
        return this;
    }

    /**
     * Textes prêts pour le gabarit.
     *
     * <p>{@code lang} et {@code footer} sont ajoutés d'office : tout
     * courriel a besoin du premier pour son attribut de langue et du
     * second parce qu'il vient de la mise en page commune. Les redemander
     * à chaque appelant reviendrait à laisser un courriel partir un jour
     * sans, avec un document annoncé dans la mauvaise langue.</p>
     */
    public Map<String, String> build() {
        Map<String, String> out = new LinkedHashMap<>(values);
        out.putIfAbsent("lang", Locales.tag(locale));
        out.putIfAbsent("footer", Messages.msg(locale, "m.mail-footer"));
        return Map.copyOf(out);
    }
}
