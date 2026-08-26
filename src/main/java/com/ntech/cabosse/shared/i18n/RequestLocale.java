package com.ntech.cabosse.shared.i18n;

import jakarta.enterprise.context.RequestScoped;

import java.util.Locale;

/**
 * Langue de la requête en cours, posée par {@link LocaleFilter} depuis
 * l'en-tête {@code Accept-Language}. Français par défaut : c'est la langue
 * de référence du produit, et celle des traitements hors requête
 * (drainer de notifications, migrations).
 */
@RequestScoped
public class RequestLocale {

    private Locale locale = Locale.FRENCH;

    public Locale locale() {
        return locale;
    }

    public void set(Locale locale) {
        this.locale = locale;
    }
}
