package com.ntech.cabosse.tracabilite.service;

import com.ntech.cabosse.production.entity.ConsumptionLine;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.production.entity.ManufacturingOrderEntity;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.production.repository.ManufacturingOrderRepository;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockMovementEntity;
import com.ntech.cabosse.stock.repository.StockMovementRepository;
import com.ntech.cabosse.tracabilite.dto.DaughterLotDto;
import com.ntech.cabosse.tracabilite.dto.LotCertificationDto;
import com.ntech.cabosse.tracabilite.dto.LotIndexEntryDto;
import com.ntech.cabosse.tracabilite.dto.LotTraceResponseDto;
import com.ntech.cabosse.tracabilite.dto.StageDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service de traçabilité — reconstitue la généalogie d'un lot à partir
 * de l'OF qui l'a produit, des matières consommées, et des ventes
 * consommatrices.
 *
 * <p><strong>Inférence amont</strong> : les matières premières actuelles
 * ne portent pas de notion de "lot d'entrée" (un BC livré agrège son
 * stock au CMUP courant sans étiquette de lot fournisseur). Pour
 * reconstituer "d'où viennent les fèves consommées par cet OF ?", on
 * recherche les <strong>5 derniers mouvements IN</strong> antérieurs au
 * démarrage de l'OF sur le couple {@code (articleId, siteId)} de chaque
 * ligne de consommation. C'est une approximation FIFO suffisante pour
 * un audit niveau CDC, mais imparfaite : si plusieurs BC se mélangent
 * en stock, on ne peut pas physiquement séparer leurs grains.</p>
 *
 * <p><strong>Aval</strong> : exact — chaque vente ayant consommé le PF
 * porte le {@code lotRef} sur la ligne, donc sur le mouvement OUT
 * généré.</p>
 */
@ApplicationScoped
public class LotTraceService {

    /** Limite de mouvements IN remontés par matière pour l'inférence amont. */
    private static final int UPSTREAM_LIMIT = 5;
    /** Limite de lots retournés par {@link #listIndex(int)}. */
    private static final int INDEX_DEFAULT_LIMIT = 12;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Locale FR = Locale.FRENCH;

    @Inject ManufacturingOrderRepository ofs;
    @Inject StockMovementRepository movements;

    // ════════════════════════════════════════════════════════════════
    //  API publique
    // ════════════════════════════════════════════════════════════════

    public Optional<LotTraceResponseDto> findByLotRef(String lotRef) {
        if (lotRef == null || lotRef.isBlank()) return Optional.empty();
        List<ManufacturingOrderEntity> matches = ofs.findByLot(lotRef.trim());
        if (matches.isEmpty()) return Optional.empty();
        return Optional.of(buildTrace(matches.get(0)));
    }

    /** Premier lot disponible (OF complété le plus récent) — utile pour /tracabilite sans paramètre. */
    public Optional<LotTraceResponseDto> findDefault() {
        return ofs.listAll().stream()
                .filter(of -> of.lotRef != null && !of.lotRef.isBlank())
                .filter(of -> of.status == OfStatus.COMPLETED)
                .max(Comparator.comparing(of -> of.completedAt != null ? of.completedAt : of.createdAt))
                .map(this::buildTrace);
    }

    public List<LotIndexEntryDto> listIndex(int limit) {
        int cap = limit <= 0 ? INDEX_DEFAULT_LIMIT : Math.min(limit, 50);
        return ofs.listAll().stream()
                .filter(of -> of.lotRef != null && !of.lotRef.isBlank())
                .sorted(Comparator.comparing(
                        (ManufacturingOrderEntity of) -> of.completedAt != null ? of.completedAt
                                : of.startedAt != null ? of.startedAt
                                : of.createdAt
                ).reversed())
                .limit(cap)
                .map(of -> new LotIndexEntryDto(
                        of.lotRef,
                        of.finishedProductName,
                        of.completedAt != null
                                ? LocalDate.ofInstant(of.completedAt, ZoneOffset.UTC).toString()
                                : (of.scheduledDate != null ? of.scheduledDate.toString() : null)
                ))
                .toList();
    }

    // ════════════════════════════════════════════════════════════════
    //  Construction de la trace complète
    // ════════════════════════════════════════════════════════════════

    private LotTraceResponseDto buildTrace(ManufacturingOrderEntity of) {
        UpstreamInference upstream = inferUpstream(of);
        List<StockMovementEntity> outs = movements.findByLotRef(of.lotRef).stream()
                .filter(m -> m.kind == MovementKind.OUT
                        && m.sourceType == MovementSource.SALE)
                .toList();

        List<StageDto> stages = new ArrayList<>();
        int pos = 1;
        StageDto origine = buildOrigineStage(of, upstream, pos++);
        if (origine != null) stages.add(origine);
        StageDto achat = buildAchatStage(upstream, pos++);
        if (achat != null) stages.add(achat);
        stages.add(buildProductionStage(of, pos++));
        stages.add(buildLotsFillesStage(of, pos++));
        StageDto livraison = buildLivraisonStage(of, outs, pos);
        if (livraison != null) stages.add(livraison);

        List<DaughterLotDto> daughters = buildDaughterLots(of, outs);
        String periodLabel = formatPeriod(of);
        int durationDays = computeDurationDays(of);

        return new LotTraceResponseDto(
                of.lotRef,
                upstream.dominantSupplier != null ? upstream.dominantSupplier : "—",
                buildOrigineDesc(of, upstream),
                of.producedQty != null ? of.producedQty : of.plannedQty,
                of.finishedProductUnit,
                formatHarvestPeriod(of),
                upstream.dominantSupplier != null ? upstream.dominantSupplier : "—",
                upstream.dominantMaterial != null ? upstream.dominantMaterial : "—",
                List.<LotCertificationDto>of(),
                null, // publicUrl — branchera sur tenant.publicDomain quand dispo
                periodLabel,
                durationDays,
                stages,
                daughters
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  Inférence amont
    // ════════════════════════════════════════════════════════════════

    private record UpstreamSource(MovementSource type, String ref, String label,
                                  Instant when, String articleName) {}

    private record UpstreamInference(
            List<UpstreamSource> sources,
            String dominantSupplier,
            String dominantMaterial
    ) {}

    private UpstreamInference inferUpstream(ManufacturingOrderEntity of) {
        if (of.consumptionLines == null || of.consumptionLines.isEmpty()) {
            return new UpstreamInference(List.of(), null, null);
        }
        Instant cutoff = of.startedAt != null ? of.startedAt : of.createdAt;
        if (cutoff == null) cutoff = Instant.now();

        List<UpstreamSource> sources = new ArrayList<>();
        Map<String, Integer> supplierCount = new HashMap<>();
        Map<String, Integer> materialCount = new HashMap<>();
        for (ConsumptionLine line : of.consumptionLines) {
            if (line.articleId == null) continue;
            // Note : on cherche les mouvements sur le site de l'OF — pas porté
            // directement sur la ligne, on prend celui de l'OF.
            List<StockMovementEntity> ins = movements.findRecentInsBefore(
                    line.articleId, of.siteId, cutoff, UPSTREAM_LIMIT);
            for (StockMovementEntity m : ins) {
                if (m.sourceType == null) continue;
                String label = formatSourceLabel(m);
                sources.add(new UpstreamSource(
                        m.sourceType, m.sourceRef, label, m.occurredAt,
                        line.articleName));
                if (label != null && !label.isBlank()) {
                    supplierCount.merge(label, 1, Integer::sum);
                }
            }
            if (line.articleName != null) {
                materialCount.merge(line.articleName, 1, Integer::sum);
            }
        }
        String dominantSupplier = pickDominant(supplierCount);
        String dominantMaterial = pickDominant(materialCount);
        return new UpstreamInference(sources, dominantSupplier, dominantMaterial);
    }

    private static String pickDominant(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Convertit la "source" d'un mouvement IN en libellé exploitable
     * (typiquement le n° de BC / RD). Le repository ne donne pas le nom
     * du fournisseur directement — pour ça il faudrait re-joindre sur
     * PurchaseOrderEntity / DirectReceiptEntity. Au MVP, on se contente
     * de l'identifiant pièce, l'utilisateur peut naviguer dessus.
     */
    private static String formatSourceLabel(StockMovementEntity m) {
        if (m.sourceRef != null && !m.sourceRef.isBlank()) return m.sourceRef;
        return m.sourceType != null ? m.sourceType.name() : "—";
    }

    // ════════════════════════════════════════════════════════════════
    //  Construction des stages
    // ════════════════════════════════════════════════════════════════

    private StageDto buildOrigineStage(ManufacturingOrderEntity of,
                                       UpstreamInference upstream, int pos) {
        if (upstream.sources.isEmpty()) return null;
        Set<String> suppliers = new LinkedHashSet<>();
        Set<String> materials = new LinkedHashSet<>();
        for (UpstreamSource s : upstream.sources) {
            if (s.label != null) suppliers.add(s.label);
            if (s.articleName != null) materials.add(s.articleName);
        }
        List<String> details = new ArrayList<>();
        details.add(Messages.msg("m.trc-origin-materials", String.join(", ", materials)));
        if (!suppliers.isEmpty()) {
            details.add(Messages.msg("m.trc-source-documents", String.join(", ", suppliers)));
        }
        Instant earliest = upstream.sources.stream()
                .map(UpstreamSource::when)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(of.createdAt);
        return new StageDto(
                "origine",
                "origine",
                formatPosition(pos),
                Messages.msg("m.trc-origin-stage"),
                earliest != null ? LocalDate.ofInstant(earliest, ZoneOffset.UTC).toString() : "",
                null, null,
                details,
                upstream.dominantSupplier != null ? upstream.dominantSupplier : "—",
                "validated"
        );
    }

    private StageDto buildAchatStage(UpstreamInference upstream, int pos) {
        if (upstream.sources.isEmpty()) return null;
        Set<String> refs = new LinkedHashSet<>();
        for (UpstreamSource s : upstream.sources) {
            if (s.ref != null) refs.add(s.ref);
        }
        if (refs.isEmpty()) return null;
        List<String> details = new ArrayList<>();
        details.add(Messages.msg("m.trc-identified-receipts", String.join(", ", refs)));
        details.add(Messages.msg("m.trc-fifo-inference", String.valueOf(UPSTREAM_LIMIT)));
        // pieceRef = première référence (la plus probable comme source)
        String first = refs.iterator().next();
        String href = first.startsWith("BC-") ? "/app/achats?q=" + first
                : first.startsWith("RD-") ? "/app/achats/receptions?q=" + first
                : null;
        Instant earliest = upstream.sources.stream()
                .map(UpstreamSource::when)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        return new StageDto(
                "achat",
                "achat",
                formatPosition(pos),
                Messages.msg("m.trc-supplier-receipts"),
                earliest != null ? LocalDate.ofInstant(earliest, ZoneOffset.UTC).toString() : "",
                first, href,
                details,
                upstream.dominantSupplier != null ? upstream.dominantSupplier : "—",
                "validated"
        );
    }

    private StageDto buildProductionStage(ManufacturingOrderEntity of, int pos) {
        List<String> details = new ArrayList<>();
        if (of.recipeName != null) details.add(Messages.msg("m.trc-recipe", of.recipeName));
        if (of.plannedQty != null && of.finishedProductUnit != null) {
            details.add(Messages.msg("m.trc-planned-qty", of.plannedQty + " " + of.finishedProductUnit));
        }
        if (of.producedQty != null && of.finishedProductUnit != null) {
            details.add(Messages.msg("m.trc-produced-qty", of.producedQty + " " + of.finishedProductUnit));
        }
        if (of.actualDurationHours != null) {
            details.add(Messages.msg("m.trc-duration", of.actualDurationHours + " h"));
        }
        if (of.operatorsCount != null) {
            details.add(Messages.msg("m.trc-operators", of.operatorsCount));
        }
        String status = switch (of.status) {
            case COMPLETED -> "validated";
            case IN_PROGRESS -> "in-progress";
            case DRAFT -> "pending";
            case CANCELLED -> "pending";
        };
        Instant when = of.completedAt != null ? of.completedAt
                : (of.startedAt != null ? of.startedAt : of.createdAt);
        return new StageDto(
                "production",
                "production",
                formatPosition(pos),
                Messages.msg("m.trc-production-workshop"),
                when != null ? LocalDate.ofInstant(when, ZoneOffset.UTC).toString() : "",
                of.ref,
                "/app/production/" + of.id,
                details,
                of.siteName != null ? of.siteName : "—",
                status
        );
    }

    private StageDto buildLotsFillesStage(ManufacturingOrderEntity of, int pos) {
        List<String> details = new ArrayList<>();
        if (of.finishedProductName != null) {
            details.add(Messages.msg("m.trc-finished-product", of.finishedProductName));
        }
        BigDecimalSafe q = new BigDecimalSafe(of.producedQty != null ? of.producedQty : of.plannedQty);
        if (q.value != null && of.finishedProductUnit != null) {
            details.add(Messages.msg("m.trc-quantity", q.value + " " + of.finishedProductUnit));
        }
        return new StageDto(
                "lots-filles",
                "lots-filles",
                formatPosition(pos),
                Messages.msg("m.trc-produced-lot"),
                of.completedAt != null
                        ? LocalDate.ofInstant(of.completedAt, ZoneOffset.UTC).toString()
                        : "",
                of.lotRef, null,
                details,
                of.siteName != null ? of.siteName : "—",
                of.status == OfStatus.COMPLETED ? "validated" : "in-progress"
        );
    }

    private StageDto buildLivraisonStage(ManufacturingOrderEntity of,
                                          List<StockMovementEntity> outs, int pos) {
        if (outs.isEmpty()) return null;
        Set<String> refs = new LinkedHashSet<>();
        for (StockMovementEntity m : outs) {
            if (m.sourceRef != null) refs.add(m.sourceRef);
        }
        List<String> details = new ArrayList<>();
        details.add("Ventes consommatrices : " + String.join(", ", refs));
        details.add(Messages.msg("m.trc-total-delivered", outs.stream()
                        .map(m -> m.quantitySigned != null ? m.quantitySigned.abs() : java.math.BigDecimal.ZERO)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                + " " + (of.finishedProductUnit != null ? of.finishedProductUnit : "")));
        Instant latest = outs.stream()
                .map(m -> m.occurredAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        String first = refs.iterator().next();
        return new StageDto(
                "livraison",
                "livraison",
                formatPosition(pos),
                Messages.msg("m.trc-customer-deliveries"),
                latest != null ? LocalDate.ofInstant(latest, ZoneOffset.UTC).toString() : "",
                first, "/app/ventes?q=" + first,
                details,
                "—",
                "validated"
        );
    }

    private List<DaughterLotDto> buildDaughterLots(ManufacturingOrderEntity of,
                                                   List<StockMovementEntity> outs) {
        Set<String> destinations = new LinkedHashSet<>();
        for (StockMovementEntity m : outs) {
            if (m.sourceRef != null) destinations.add(m.sourceRef);
        }
        java.math.BigDecimal qty = of.producedQty != null ? of.producedQty : of.plannedQty;
        return List.of(new DaughterLotDto(
                of.lotRef,
                of.finishedProductName != null ? of.finishedProductName : "—",
                qty,
                of.finishedProductUnit != null ? of.finishedProductUnit : "",
                new ArrayList<>(destinations)
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers de formatage
    // ════════════════════════════════════════════════════════════════

    private static String buildOrigineDesc(ManufacturingOrderEntity of, UpstreamInference up) {
        StringBuilder sb = new StringBuilder();
        if (up.dominantMaterial != null) {
            sb.append(up.dominantMaterial);
        }
        if (of.siteName != null) {
            if (sb.length() > 0) sb.append(" : ");
            sb.append(Messages.msg("m.trc-processed-at", of.siteName));
        }
        return sb.length() == 0 ? Messages.msg("m.trc-origin-unknown") : sb.toString();
    }

    private String formatHarvestPeriod(ManufacturingOrderEntity of) {
        Instant when = of.startedAt != null ? of.startedAt
                : (of.scheduledDate != null
                        ? of.scheduledDate.atStartOfDay(ZoneOffset.UTC).toInstant()
                        : of.createdAt);
        if (when == null) return "";
        LocalDate d = LocalDate.ofInstant(when, ZoneOffset.UTC);
        return capitalize(d.getMonth().getDisplayName(TextStyle.FULL, FR)) + " " + d.getYear();
    }

    private String formatPeriod(ManufacturingOrderEntity of) {
        Instant start = of.startedAt != null ? of.startedAt : of.createdAt;
        Instant end = of.completedAt != null ? of.completedAt : Instant.now();
        if (start == null) return "";
        LocalDate ds = LocalDate.ofInstant(start, ZoneOffset.UTC);
        LocalDate de = LocalDate.ofInstant(end, ZoneOffset.UTC);
        if (ds.equals(de)) {
            return ds.getDayOfMonth() + " " + ds.getMonth().getDisplayName(TextStyle.FULL, FR)
                    + " " + ds.getYear();
        }
        return ds.getDayOfMonth() + (ds.getMonth() == de.getMonth()
                ? "–" + de.getDayOfMonth() + " " + de.getMonth().getDisplayName(TextStyle.FULL, FR)
                  + " " + de.getYear()
                : " " + ds.getMonth().getDisplayName(TextStyle.FULL, FR)
                  + " au " + de.getDayOfMonth() + " " + de.getMonth().getDisplayName(TextStyle.FULL, FR)
                  + " " + de.getYear());
    }

    private int computeDurationDays(ManufacturingOrderEntity of) {
        Instant start = of.startedAt != null ? of.startedAt : of.createdAt;
        Instant end = of.completedAt != null ? of.completedAt : Instant.now();
        if (start == null) return 0;
        long days = Duration.between(start, end).toDays();
        return (int) Math.max(0, days);
    }

    private static String formatPosition(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Mini wrapper pour éviter l'import explicite quand on n'utilise pas
    // d'opération arithmétique sur la valeur — uniquement pour son toString.
    private static class BigDecimalSafe {
        final java.math.BigDecimal value;
        BigDecimalSafe(java.math.BigDecimal v) { this.value = v; }
    }
}
