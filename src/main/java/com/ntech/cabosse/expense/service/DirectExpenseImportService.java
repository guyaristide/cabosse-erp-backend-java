package com.ntech.cabosse.expense.service;

import com.ntech.cabosse.expense.dto.CreateDirectExpenseDto;
import com.ntech.cabosse.expense.dto.DirectExpenseImportCommitResponseDto;
import com.ntech.cabosse.expense.dto.DirectExpenseImportPreviewDto;
import com.ntech.cabosse.expense.dto.DirectExpenseImportPreviewDto.FieldIssue;
import com.ntech.cabosse.expense.dto.DirectExpenseImportPreviewDto.Normalized;
import com.ntech.cabosse.expense.dto.DirectExpenseImportPreviewDto.Row;
import com.ntech.cabosse.expense.dto.DirectExpenseImportPreviewDto.Status;
import com.ntech.cabosse.expense.dto.DirectExpenseImportRowDto;
import com.ntech.cabosse.expense.entity.DirectExpenseKind;
import com.ntech.cabosse.expensetype.entity.ExpenseTypeEntity;
import com.ntech.cabosse.expensetype.repository.ExpenseTypeRepository;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Charger les dépenses d'un mois depuis un tableur.
 *
 * <p>Les saisir une à une tenait tant qu'elles étaient rares. Une
 * structure qui relève trente lignes de carburant, de téléphone et de
 * fournitures en fin de mois ne le fait pas trente fois à la main
 * (demandé le 04/10/2026).</p>
 *
 * <p>Même parcours que les autres imports du produit : un aperçu qui ne
 * touche à rien, puis une application qui ne reprend que les lignes
 * prêtes. Une dépense engage la caisse, et un fichier venu d'un tableur
 * a toujours une colonne décalée quelque part.</p>
 *
 * <p>Le prestataire est facultatif, comme à la saisie : nommé et connu,
 * la dette va sur son compte ; inconnu, la ligne le signale sans la
 * refuser et la dette reste au collectif fournisseurs. Refuser une
 * petite dépense faute de fiche arrêterait la caisse sur un achat de
 * crédit téléphonique.</p>
 */
@ApplicationScoped
public class DirectExpenseImportService {

    @Inject DirectExpenseService expenses;
    @Inject SupplierRepository suppliers;
    @Inject ExpenseTypeRepository expenseTypes;

    public DirectExpenseImportPreviewDto preview(List<DirectExpenseImportRowDto> input) {
        if (input == null) input = List.of();

        Map<String, SupplierEntity> knownSuppliers = new LinkedHashMap<>();
        for (SupplierEntity s : suppliers.listAll()) {
            if (s.name != null) knownSuppliers.putIfAbsent(normalize(s.name), s);
            if (s.code != null) knownSuppliers.putIfAbsent(normalize(s.code), s);
        }
        Map<String, ExpenseTypeEntity> knownTypes = new LinkedHashMap<>();
        for (ExpenseTypeEntity t : expenseTypes.listAll()) {
            if (t.name != null) knownTypes.putIfAbsent(normalize(t.name), t);
            if (t.code != null) knownTypes.putIfAbsent(normalize(t.code), t);
        }

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0;
        int invalid = 0;
        BigDecimal total = BigDecimal.ZERO;

        for (DirectExpenseImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();
            List<FieldIssue> notices = new ArrayList<>();

            DirectExpenseKind kind = parseKind(raw.kind());
            if (kind == null) {
                issues.add(new FieldIssue("kind", Messages.msg("m.imp-dep-kind-unknown", nz(raw.kind()))));
            }

            LocalDate date = parseDate(raw.expenseDate());
            if (raw.expenseDate() != null && !raw.expenseDate().isBlank() && date == null) {
                issues.add(new FieldIssue("expenseDate",
                        Messages.msg("m.imp-dep-date-unreadable", raw.expenseDate())));
            }

            String label = blankToNull(raw.label());
            if (label == null) {
                issues.add(new FieldIssue("label", Messages.msg("m.imp-dep-label-required")));
            }

            String chargeAccount = blankToNull(raw.chargeAccount());
            ExpenseTypeEntity type = knownTypes.get(normalize(raw.expenseTypeName()));
            if (type == null && blankToNull(raw.expenseTypeName()) != null) {
                notices.add(new FieldIssue("expenseTypeName",
                        Messages.msg("m.imp-dep-type-unknown", raw.expenseTypeName())));
            }
            // Le compte de charge vient du fichier, ou du type de dépense
            // quand il le porte. Sans l'un ni l'autre, la ligne n'a nulle
            // part où s'imputer.
            if (chargeAccount == null && type != null) chargeAccount = type.syscohadaAccount;
            if (chargeAccount == null) {
                issues.add(new FieldIssue("chargeAccount",
                        Messages.msg("m.imp-dep-charge-account-required")));
            }

            SupplierEntity supplier = knownSuppliers.get(normalize(raw.supplierName()));
            if (supplier == null && blankToNull(raw.supplierName()) != null) {
                // Signalé, pas refusé : la dette reste au collectif, et
                // la dépense se paie quand même.
                notices.add(new FieldIssue("supplierName",
                        Messages.msg("m.imp-dep-supplier-unknown", raw.supplierName())));
            }

            BigDecimal amountHt = parseDecimal(raw.amountHt());
            if (amountHt == null || amountHt.signum() <= 0) {
                issues.add(new FieldIssue("amountHt", Messages.msg("m.imp-dep-amount-required")));
            }
            BigDecimal vatRate = parseDecimal(raw.vatRatePct());
            if (vatRate == null) vatRate = BigDecimal.ZERO;
            if (vatRate.signum() < 0 || vatRate.compareTo(BigDecimal.valueOf(100)) > 0) {
                issues.add(new FieldIssue("vatRatePct",
                        Messages.msg("m.imp-dep-vat-out-of-range", raw.vatRatePct())));
            }

            BigDecimal ttc = amountHt == null ? null : amountHt.add(
                    amountHt.multiply(vatRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP));

            Status status = issues.isEmpty() ? Status.READY : Status.INVALID;
            if (status == Status.READY) {
                ready++;
                total = total.add(ttc);
            } else {
                invalid++;
            }

            rows.add(new Row(raw.rowNumber(), status,
                    new Normalized(
                            kind == null ? null : kind.name(),
                            date == null ? null : date.toString(),
                            supplier == null ? null : supplier.id,
                            supplier == null ? blankToNull(raw.supplierName()) : supplier.name,
                            type == null ? null : type.id,
                            type == null ? null : type.name,
                            chargeAccount, label, amountHt, vatRate, ttc),
                    issues, notices));
        }
        return new DirectExpenseImportPreviewDto(input.size(), ready, invalid, 0, total, rows);
    }

    /**
     * Applique les lignes prêtes.
     *
     * <p>Une ligne refusée à l'écriture ne fait pas échouer le fichier :
     * elle ressort avec son motif, et les autres sont passées. Tout
     * annuler pour une ligne obligerait à relancer trente dépenses pour
     * une seule erreur.</p>
     */
    public DirectExpenseImportCommitResponseDto commit(List<DirectExpenseImportRowDto> input) {
        DirectExpenseImportPreviewDto preview = preview(input);
        List<UUID> createdIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();

        for (Row row : preview.rows()) {
            if (row.status() != Status.READY) {
                skipped.add(row);
                continue;
            }
            Normalized n = row.normalized();
            try {
                var created = expenses.create(new CreateDirectExpenseDto(
                        n.kind(),
                        n.expenseDate() == null ? null : LocalDate.parse(n.expenseDate()),
                        n.supplierId(),
                        n.expenseTypeId(),
                        n.chargeAccount(),
                        n.label(),
                        blankToNull(rawOf(input, row.rowNumber(), DirectExpenseImportRowDto::periodLabel)),
                        blankToNull(rawOf(input, row.rowNumber(), DirectExpenseImportRowDto::allocationKeyCode)),
                        n.amountHt(),
                        n.vatRatePct(),
                        // Le mode n'engage plus la caisse : la dépense se
                        // constate, et le règlement se décide à la
                        // trésorerie.
                        "BANK_TRANSFER",
                        null,
                        blankToNull(rawOf(input, row.rowNumber(), DirectExpenseImportRowDto::notes))));
                createdIds.add(created.id());
            } catch (RuntimeException e) {
                skipped.add(new Row(row.rowNumber(), Status.INVALID, n,
                        List.of(new FieldIssue("_", e.getMessage())), row.notices()));
            }
        }
        return new DirectExpenseImportCommitResponseDto(
                preview.totalRows(), createdIds.size(), skipped.size(), createdIds, skipped);
    }

    /** Relit une colonne brute que l'aperçu ne normalise pas. */
    private static String rawOf(List<DirectExpenseImportRowDto> input, int rowNumber,
                                java.util.function.Function<DirectExpenseImportRowDto, String> get) {
        return input.stream()
                .filter(r -> r.rowNumber() == rowNumber)
                .findFirst().map(get).orElse(null);
    }

    /**
     * La nature, écrite en code ou en toutes lettres.
     *
     * <p>Le fichier vient du tableur de la structure : « Abonnement » y
     * est plus probable que {@code CONTRACT}.</p>
     */
    private static DirectExpenseKind parseKind(String raw) {
        String key = normalize(raw);
        if (key.isEmpty()) return DirectExpenseKind.PETTY_CASH;
        if (key.startsWith("contract") || key.startsWith("abonnement")
                || key.startsWith("contrat") || key.startsWith("subscription")) {
            return DirectExpenseKind.CONTRACT;
        }
        if (key.startsWith("petty") || key.startsWith("petite") || key.startsWith("caisse")) {
            return DirectExpenseKind.PETTY_CASH;
        }
        return null;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String stripped = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            return LocalDate.parse(v);
        } catch (Exception ignored) {
            // Le tableur écrit la date du pays : 04/10/2026.
            try {
                return LocalDate.parse(v, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim().replace(" ", "").replace(" ", "").replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }
}
