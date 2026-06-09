package com.ntech.cabosse.shared.money;

import com.ntech.cabosse.catalog.entity.CurrencyEntity;
import com.ntech.cabosse.catalog.repository.CurrencyRepository;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formattage des montants avec respect strict de la devise du tenant
 * (catalogue {@code currencies}). Sert à toute génération côté serveur :
 * libellés d'écritures comptables, exports, e-mails, PDF.
 *
 * <p>Convention :
 * <ul>
 *   <li>Séparateur de milliers = espace insécable {@code  } (style FR).</li>
 *   <li>Séparateur décimal = virgule (style FR).</li>
 *   <li>Décimales = nombre porté par la devise (0 pour XOF/XAF, 2 pour EUR/USD…).</li>
 *   <li>Symbole positionné selon {@link CurrencyEntity#position} :
 *       {@code BEFORE} produit {@code "$ 1 234"}, {@code AFTER}
 *       produit {@code "1 234 FCFA"}.</li>
 * </ul>
 *
 * <p>Les devises inconnues du catalogue tombent sur un fallback générique
 * (2 décimales, symbole = code). Cache 5 min pour limiter les lectures
 * de catalogue.</p>
 */
@ApplicationScoped
public class MoneyFormatter {

    @Inject CurrencyRepository currencies;

    /**
     * Format complet : "1 234 567 FCFA", "1 234,56 €", "$ 1,234.56" (en
     * fonction de la position).
     */
    public String format(BigDecimal value, String currencyCode) {
        if (value == null) return "—";
        CurrencyDescriptor d = describe(currencyCode);
        BigDecimal rounded = value.setScale(d.decimalPlaces(), RoundingMode.HALF_UP);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        StringBuilder pattern = new StringBuilder("#,##0");
        if (d.decimalPlaces() > 0) {
            pattern.append('.');
            for (int i = 0; i < d.decimalPlaces(); i++) pattern.append('0');
        }
        DecimalFormat fmt = new DecimalFormat(pattern.toString(), symbols);
        String amount = fmt.format(rounded);
        return "BEFORE".equalsIgnoreCase(d.position())
                ? d.symbol() + " " + amount
                : amount + " " + d.symbol();
    }

    /** Variante sans symbole — utile pour les exports tabulaires où la colonne porte déjà la devise. */
    public String formatPlain(BigDecimal value, String currencyCode) {
        if (value == null) return "";
        CurrencyDescriptor d = describe(currencyCode);
        BigDecimal rounded = value.setScale(d.decimalPlaces(), RoundingMode.HALF_UP);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        StringBuilder pattern = new StringBuilder("#,##0");
        if (d.decimalPlaces() > 0) {
            pattern.append('.');
            for (int i = 0; i < d.decimalPlaces(); i++) pattern.append('0');
        }
        return new DecimalFormat(pattern.toString(), symbols).format(rounded);
    }

    /** Symbole isolé ("FCFA", "€", "$"…) — utile pour les libellés courts. */
    public String symbol(String currencyCode) {
        return describe(currencyCode).symbol();
    }

    @CacheResult(cacheName = "currency-descriptor")
    public CurrencyDescriptor describe(String currencyCode) {
        String code = currencyCode == null || currencyCode.isBlank()
                ? "XOF" : currencyCode.toUpperCase();
        return currencies.findByCode(code)
                .map(c -> new CurrencyDescriptor(c.code, c.symbol, c.decimalPlaces, c.position))
                .orElseGet(() -> new CurrencyDescriptor(code, code, 2, "AFTER"));
    }

    /** Vue compacte du descripteur de devise — cacheable. */
    public record CurrencyDescriptor(String code, String symbol, int decimalPlaces, String position) {}
}
