package com.ntech.cabosse.agriculture.harvest.service;

import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportCommitResponseDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportPreviewDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportPreviewDto.FieldIssue;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportPreviewDto.Normalized;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportPreviewDto.Row;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportPreviewDto.Status;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportRowDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestUpsertDto;
import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;
import com.ntech.cabosse.agriculture.harvest.repository.HarvestRepository;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.shared.imports.FuzzyLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Import de récoltes depuis un fichier.
 *
 * <p>Le plus simple des trois imports de l'amont, mais le plus dépendant :
 * sans parcelles chargées, aucune ligne ne se rattache. Une récolte est un
 * événement, pas une fiche : c'est le couple parcelle et date qui
 * l'identifie, et une seconde lecture du même fichier met à jour la récolte
 * au lieu de la compter deux fois.</p>
 *
 * <p>Une parcelle introuvable est une erreur, pas un avertissement : une
 * récolte sans parcelle n'existe pas. En revanche, une date qui sort de la
 * période de la campagne est signalée sans être bloquante — c'est souvent
 * une faute de saisie, parfois une récolte tardive bien réelle.</p>
 */
@ApplicationScoped
public class HarvestImportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
    );

    @Inject HarvestRepository harvests;
    @Inject HarvestService harvestService;
    @Inject ParcelRepository parcels;
    @Inject CampaignResolver campaignResolver;

    // ─── Aperçu ─────────────────────────────────────────────────────

    public HarvestImportPreviewDto preview(List<HarvestImportRowDto> input, UUID campaignId) {
        if (input == null || input.isEmpty()) {
            return new HarvestImportPreviewDto(0, 0, 0, 0, 0, 0, List.of());
        }

        CampaignEntity campaign = campaignResolver.resolveOptional(campaignId);
        List<ParcelEntity> allParcels = parcels.listAll();
        Set<String> keysSeen = new HashSet<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, update = 0, warning = 0, invalid = 0, duplicate = 0;

        for (HarvestImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            ParcelEntity parcel = findParcel(allParcels, raw.parcelCode(), raw.parcelName(),
                    raw.producerCode());
            if (parcel == null) {
                issues.add(new FieldIssue("parcelCode",
                        "Parcelle introuvable : importez les parcelles avant les récoltes."));
            }

            LocalDate harvestDate = parseDate(raw.harvestDate(), issues);
            if (harvestDate == null && trim(raw.harvestDate()) == null) {
                issues.add(new FieldIssue("harvestDate", "Date de récolte requise."));
            }

            BigDecimal cabosses = parseDecimal(raw.cabossesKg(), "cabossesKg", issues);
            BigDecimal freshBeans = parseDecimal(raw.freshBeansKg(), "freshBeansKg", issues);
            if (isEmptyQuantity(cabosses) && isEmptyQuantity(freshBeans)) {
                issues.add(new FieldIssue("cabossesKg",
                        "Aucune quantité récoltée : renseignez les cabosses ou les fèves fraîches."));
            }

            Normalized normalized = new Normalized(
                    parcel != null ? parcel.id : null,
                    parcel != null ? parcel.code : trim(raw.parcelCode()),
                    parcel != null ? parcel.name : trim(raw.parcelName()),
                    parcel != null ? parcel.memberId : null,
                    parcel != null ? parcel.memberName : null,
                    harvestDate != null ? harvestDate.format(ISO) : null,
                    cabosses, freshBeans,
                    trim(raw.qualityNotes()), trim(raw.notes()));

            HarvestEntity match = parcel != null && harvestDate != null
                    ? findExisting(harvests.listByParcel(parcel.id), harvestDate)
                    : null;

            String key = parcel != null && harvestDate != null
                    ? parcel.id + "|" + harvestDate
                    : null;

            Status status;
            if (!issues.isEmpty()) {
                status = Status.INVALID;
                invalid++;
            } else if (key != null && !keysSeen.add(key)) {
                issues.add(new FieldIssue("harvestDate",
                        "Même parcelle et même date qu'une ligne précédente du fichier."));
                status = Status.DUPLICATE_IN_FILE;
                duplicate++;
            } else if (outsideCampaign(harvestDate, campaign)) {
                issues.add(new FieldIssue("harvestDate",
                        "Date hors de la période de la campagne « " + campaign.label + " »."));
                status = Status.WARNING;
                warning++;
            } else if (match != null) {
                status = Status.UPDATE;
                update++;
            } else {
                status = Status.READY;
                ready++;
            }

            rows.add(new Row(raw.rowNumber(), status, normalized,
                    match != null ? match.id : null,
                    match != null ? "Parcelle et date" : null,
                    issues));
        }

        return new HarvestImportPreviewDto(input.size(), ready, update, warning, invalid, duplicate, rows);
    }

    // ─── Application ────────────────────────────────────────────────

    /**
     * @param campaignId      campagne de rattachement de tout le fichier
     * @param includeWarnings applique aussi les récoltes datées hors période
     */
    public HarvestImportCommitResponseDto commit(List<HarvestImportRowDto> input,
                                                 UUID campaignId, boolean includeWarnings) {
        HarvestImportPreviewDto preview = preview(input, campaignId);
        CampaignEntity campaign = campaignResolver.resolve(campaignId);

        List<UUID> created = new ArrayList<>();
        List<UUID> updated = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();

        for (Row row : preview.rows()) {
            boolean applicable = row.status() == Status.READY
                    || row.status() == Status.UPDATE
                    || (row.status() == Status.WARNING && includeWarnings);
            if (!applicable || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            Normalized n = row.normalized();
            try {
                HarvestUpsertDto payload = new HarvestUpsertDto(
                        n.parcelId(), n.memberId(), campaign.id,
                        LocalDate.parse(n.harvestDate()),
                        n.cabossesKg(), n.freshBeansKg(),
                        n.qualityNotes(), n.notes());

                if (row.matchedHarvestId() != null) {
                    HarvestResponseDto dto = harvestService.update(row.matchedHarvestId(), payload);
                    updated.add(dto.id());
                } else {
                    HarvestResponseDto dto = harvestService.create(payload);
                    created.add(dto.id());
                }
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(),
                        row.matchedHarvestId(), row.matchedOn(), issues));
            }
        }

        return new HarvestImportCommitResponseDto(
                preview.totalRows(), created.size(), updated.size(), skipped.size(),
                campaign.label, created, updated, skipped);
    }

    // ─── Rapprochements ─────────────────────────────────────────────

    /** Parcelle par code, sinon par nom, restreint au producteur si fourni. */
    private static ParcelEntity findParcel(List<ParcelEntity> all, String rawCode,
                                           String rawName, String rawProducerCode) {
        String code = trim(rawCode);
        if (code != null) {
            Optional<ParcelEntity> byCode = all.stream()
                    .filter(p -> code.equalsIgnoreCase(p.code))
                    .findFirst();
            if (byCode.isPresent()) return byCode.get();
        }
        String name = trim(rawName);
        if (name != null) {
            // Les fichiers retravaillés mettent souvent le code plantation
            // dans la colonne « Parcelle » : on le tente d'abord comme code.
            Optional<ParcelEntity> nameAsCode = all.stream()
                    .filter(p -> name.equalsIgnoreCase(p.code))
                    .findFirst();
            if (nameAsCode.isPresent()) return nameAsCode.get();

            String canonical = FuzzyLabels.canonical(name);
            List<ParcelEntity> byName = all.stream()
                    .filter(p -> FuzzyLabels.canonical(p.name).equals(canonical))
                    .toList();
            if (byName.size() == 1) return byName.get(0);
            // Plusieurs parcelles homonymes : le producteur tranche.
            String producerCode = trim(rawProducerCode);
            if (byName.size() > 1 && producerCode != null) {
                return byName.stream()
                        .filter(p -> p.memberName != null
                                && FuzzyLabels.canonical(p.memberName).contains(
                                        FuzzyLabels.canonical(producerCode)))
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    /** Une récolte est identifiée par sa parcelle et sa date, pas par un code. */
    private static HarvestEntity findExisting(List<HarvestEntity> parcelHarvests, LocalDate date) {
        return parcelHarvests.stream()
                .filter(h -> date.equals(h.harvestDate))
                .findFirst()
                .orElse(null);
    }

    private static boolean outsideCampaign(LocalDate date, CampaignEntity campaign) {
        if (date == null || campaign == null) return false;
        boolean beforeStart = campaign.startDate != null && date.isBefore(campaign.startDate);
        boolean afterEnd = campaign.endDate != null && date.isAfter(campaign.endDate);
        return beforeStart || afterEnd;
    }

    // ─── Conversions ────────────────────────────────────────────────

    private static boolean isEmptyQuantity(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static LocalDate parseDate(String raw, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return null;
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (RuntimeException ignored) {
                // format suivant
            }
        }
        issues.add(new FieldIssue("harvestDate", "Date « " + raw + " » illisible (attendu JJ/MM/AAAA)."));
        return null;
    }

    private static BigDecimal parseDecimal(String raw, String field, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return null;
        try {
            return new BigDecimal(value.replaceAll("[\\s ]", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, "Quantité « " + raw + " » illisible."));
            return null;
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
