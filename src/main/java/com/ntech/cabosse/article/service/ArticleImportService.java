package com.ntech.cabosse.article.service;

import com.ntech.cabosse.article.dto.ArticleImportCommitResponseDto;
import com.ntech.cabosse.article.dto.ArticleImportPreviewDto;
import com.ntech.cabosse.article.dto.ArticleImportPreviewDto.FieldIssue;
import com.ntech.cabosse.article.dto.ArticleImportPreviewDto.Normalized;
import com.ntech.cabosse.article.dto.ArticleImportPreviewDto.Row;
import com.ntech.cabosse.article.dto.ArticleImportPreviewDto.Status;
import com.ntech.cabosse.article.dto.ArticleImportRowDto;
import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestre l'import en masse d'articles depuis un fichier (CSV/Excel
 * parsé côté client). Deux opérations :
 *
 * <ul>
 *   <li>{@link #preview(List)} — analyse chaque ligne, résout les
 *       valeurs (parse types FR/EN, parse Oui/Non, parse nombres),
 *       valide les champs obligatoires, détecte les doublons (vs base
 *       du tenant <em>et</em> au sein du fichier lui-même). Aucune
 *       écriture.</li>
 *   <li>{@link #commit(List)} — relance la preview puis crée
 *       effectivement les lignes en statut {@code READY}. Les lignes en
 *       erreur (INVALID / DUPLICATE_*) sont ignorées et listées dans la
 *       réponse pour affichage utilisateur (import partiel).</li>
 * </ul>
 */
@ApplicationScoped
public class ArticleImportService {

    private static final Set<String> ALLOWED_UNITS = Set.of(
            "kg", "g", "L", "mL", "pcs", "sac", "carton", "m", "m²", "m2"
    );

    @Inject ArticleRepository articles;
    @Inject ArticleService articleService;

    // ─── Preview ───────────────────────────────────────────────────

    public ArticleImportPreviewDto preview(List<ArticleImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new ArticleImportPreviewDto(0, 0, 0, 0, List.of());
        }

        // Précharger les codes existants pour le tenant — évite un appel
        // par ligne. Volume catalogue restant raisonnable (<10k).
        Set<String> existingCodes = new HashSet<>();
        for (ArticleEntity e : articles.listAll()) {
            if (e.code != null) existingCodes.add(e.code.toLowerCase(Locale.ROOT));
        }

        // Compteur intra-fichier des codes (pour détecter les doublons
        // dans le fichier lui-même, après normalisation du code).
        Map<String, Integer> firstOccurrenceByCode = new HashMap<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, invalid = 0, duplicate = 0;

        for (ArticleImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            ArticleType type = parseType(raw.type(), issues);
            String name = trimOrNull(raw.name());
            if (name == null) issues.add(new FieldIssue("name", "Nom requis."));
            else if (name.length() < 2) issues.add(new FieldIssue("name", "Nom trop court (2 caractères min)."));
            else if (name.length() > 120) issues.add(new FieldIssue("name", "Nom trop long (120 caractères max)."));

            String unit = trimOrNull(raw.unit());
            if (unit == null) {
                issues.add(new FieldIssue("unit", "Unité requise."));
            } else if (!ALLOWED_UNITS.contains(unit)) {
                issues.add(new FieldIssue("unit",
                        "Unité « " + unit + " » inconnue. Autorisées : "
                                + String.join(", ", ALLOWED_UNITS)));
            }

            String code = trimOrNull(raw.code());
            if (code != null && !code.matches("[A-Za-z0-9-]{2,40}")) {
                issues.add(new FieldIssue("code",
                        "Code invalide : 2 à 40 caractères, lettres/chiffres/tirets."));
            }
            // Si pas de code fourni, on va le dériver du nom (fait par ArticleService au commit).
            String resolvedCode = (code != null && !code.isBlank())
                    ? code
                    : (name != null ? slugify(name) : null);

            Boolean stockable = parseBoolean(raw.stockable(), issues, "stockable");
            BigDecimal alertThreshold = parseDecimal(raw.alertThreshold(), issues, "alertThreshold", false);
            BigDecimal standardCost = parseDecimal(raw.standardCost(), issues, "standardCost", false);
            BigDecimal standardSalePrice = parseDecimal(raw.standardSalePrice(), issues, "standardSalePrice", false);
            BigDecimal vatRate = parseDecimal(raw.vatRate(), issues, "vatRate", false);
            if (vatRate != null && (vatRate.signum() < 0 || vatRate.compareTo(BigDecimal.valueOf(100)) > 0)) {
                issues.add(new FieldIssue("vatRate", "TVA hors plage [0–100]."));
            }
            if (alertThreshold != null && alertThreshold.signum() < 0) {
                issues.add(new FieldIssue("alertThreshold", "Seuil d'alerte négatif interdit."));
            }
            if (standardCost != null && standardCost.signum() < 0) {
                issues.add(new FieldIssue("standardCost", "Coût standard négatif interdit."));
            }
            if (standardSalePrice != null && standardSalePrice.signum() < 0) {
                issues.add(new FieldIssue("standardSalePrice", "Prix de vente négatif interdit."));
            }

            String barcode = trimOrNull(raw.barcode());
            if (barcode != null && !barcode.matches("[A-Za-z0-9._-]+")) {
                issues.add(new FieldIssue("barcode",
                        "Code-barres : lettres/chiffres/point/tiret/souligné uniquement."));
            }
            String activityCode = trimOrNull(raw.activityCode());
            String description = trimOrNull(raw.description());

            // Détection doublons (n'a de sens que si un code candidat existe).
            Status status;
            if (!issues.isEmpty()) {
                status = Status.INVALID;
                invalid++;
            } else if (resolvedCode != null
                    && existingCodes.contains(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code",
                        "Code « " + resolvedCode + " » déjà utilisé dans le catalogue."));
                status = Status.DUPLICATE_IN_DB;
                duplicate++;
            } else if (resolvedCode != null
                    && firstOccurrenceByCode.containsKey(resolvedCode.toLowerCase(Locale.ROOT))) {
                int firstRow = firstOccurrenceByCode.get(resolvedCode.toLowerCase(Locale.ROOT));
                issues.add(new FieldIssue("code",
                        "Code « " + resolvedCode + " » déjà présent ligne " + firstRow + " du fichier."));
                status = Status.DUPLICATE_IN_FILE;
                duplicate++;
            } else {
                if (resolvedCode != null) {
                    firstOccurrenceByCode.put(resolvedCode.toLowerCase(Locale.ROOT), raw.rowNumber());
                }
                status = Status.READY;
                ready++;
            }

            Normalized normalized = (status == Status.INVALID && type == null)
                    ? null
                    : new Normalized(
                            type != null ? type.name() : null,
                            resolvedCode,
                            name,
                            unit,
                            activityCode,
                            stockable,
                            alertThreshold,
                            standardCost,
                            standardSalePrice,
                            vatRate,
                            barcode,
                            description
                    );

            rows.add(new Row(raw.rowNumber(), status, normalized, issues));
        }

        return new ArticleImportPreviewDto(input.size(), ready, invalid, duplicate, rows);
    }

    // ─── Commit ────────────────────────────────────────────────────

    public ArticleImportCommitResponseDto commit(List<ArticleImportRowDto> input) {
        ArticleImportPreviewDto preview = preview(input);
        List<UUID> createdIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();

        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            Normalized n = row.normalized();
            try {
                ArticleUpsertDto payload = new ArticleUpsertDto(
                        n.type(),
                        n.code(),
                        n.name(),
                        n.description(),
                        n.unit(),
                        n.standardCost(),
                        n.standardSalePrice(),
                        n.activityCode(),
                        n.stockable(),
                        n.alertThreshold(),
                        n.barcode(),
                        n.vatRate(),
                /* purchaseChargeAccount */ null,
                /* salesRevenueAccount */ null,
                /* defaultCostCenter */ null,
                /* defaultProgram */ null,
                /* defaultProject */ null,
                        /* unitWeightGrams */ null
                );
                ArticleResponseDto created = articleService.create(payload);
                createdIds.add(created.id());
            } catch (RuntimeException e) {
                // Conflit de code dérivé qu'on n'avait pas détecté en preview
                // (race condition très rare) — on l'ajoute aux skipped.
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(), issues));
            }
        }

        return new ArticleImportCommitResponseDto(
                preview.totalRows(),
                createdIds.size(),
                skipped.size(),
                createdIds,
                skipped
        );
    }

    // ─── Parsers ───────────────────────────────────────────────────

    /**
     * Résout un type d'article depuis une chaîne libre. Accepte les
     * codes techniques ({@code RAW_MATERIAL}, {@code FINISHED_PRODUCT},
     * {@code CONSUMABLE}, {@code PACKAGING}) et les libellés FR usuels
     * (matière première, produit fini, consommable, emballage, MP, PF…).
     */
    private static ArticleType parseType(String raw, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) {
            issues.add(new FieldIssue("type",
                    "Type requis (Matière première, Consommable, Emballage, Produit fini)."));
            return null;
        }
        String n = normalize(raw);
        return switch (n) {
            case "raw material", "raw_material", "rawmaterial", "matiere premiere",
                 "matierepremiere", "matiere 1ere", "mp" -> ArticleType.RAW_MATERIAL;
            case "finished product", "finished_product", "finishedproduct",
                 "produit fini", "produitfini", "pf" -> ArticleType.FINISHED_PRODUCT;
            case "consumable", "consommable" -> ArticleType.CONSUMABLE;
            case "packaging", "emballage" -> ArticleType.PACKAGING;
            default -> {
                issues.add(new FieldIssue("type",
                        "Type « " + raw + " » non reconnu (attendu : Matière première, "
                                + "Consommable, Emballage, Produit fini)."));
                yield null;
            }
        };
    }

    /**
     * Parse un booléen "Oui/Non" / "true/false" / "1/0". {@code null} si
     * vide (l'entité prendra son défaut). Erreur si valeur inconnue.
     */
    private static Boolean parseBoolean(String raw, List<FieldIssue> issues, String field) {
        if (raw == null || raw.isBlank()) return null;
        String n = normalize(raw);
        return switch (n) {
            case "oui", "yes", "y", "true", "vrai", "1" -> Boolean.TRUE;
            case "non", "no", "n", "false", "faux", "0" -> Boolean.FALSE;
            default -> {
                issues.add(new FieldIssue(field,
                        "Valeur « " + raw + " » invalide (attendu : Oui ou Non)."));
                yield null;
            }
        };
    }

    /**
     * Parse un nombre depuis une chaîne acceptant le format FR
     * ({@code 1 800,50}) ou US ({@code 1800.50}). Tolère les espaces
     * fines insécables. {@code null} si vide.
     */
    private static BigDecimal parseDecimal(String raw, List<FieldIssue> issues, String field, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) issues.add(new FieldIssue(field, "Valeur numérique requise."));
            return null;
        }
        String cleaned = raw
                .replace(" ", "")     // NBSP
                .replace(" ", "")     // narrow NBSP
                .replace(" ", "")
                .replace(",", ".");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, "Nombre invalide : « " + raw + " »."));
            return null;
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Normalise pour comparaison : minuscules, sans accents, sans tirets/sousligns. */
    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return n.replace("_", " ").replace("-", " ").replaceAll("\\s+", " ");
    }

    /** Slug pour les codes auto-générés (en cohérence avec ArticleService.slugify). */
    private static String slugify(String name) {
        if (name == null) return null;
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "article" : n;
    }
}
