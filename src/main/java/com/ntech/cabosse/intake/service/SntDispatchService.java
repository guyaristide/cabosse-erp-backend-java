package com.ntech.cabosse.intake.service;

import com.ntech.cabosse.intake.dto.SntAccountingRequestDto;
import com.ntech.cabosse.intake.dto.SntCommitResultDto;
import com.ntech.cabosse.intake.dto.SntDispatchPreviewDto;
import com.ntech.cabosse.intake.dto.SntDispatchRequestDto;
import com.ntech.cabosse.intake.dto.SntDispatchResultDto;
import com.ntech.cabosse.intake.dto.SntLineDto;
import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.intake.repository.IntakeNoteRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportFields;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ntech.cabosse.shared.exception.UserFacingReason;

/**
 * Répartir un extrait de traçabilité entre plusieurs bordereaux.
 *
 * <p>Un mois de retard fait des dizaines de bordereaux, et le fichier
 * national qui les couvre est unique : le charger bordereau par
 * bordereau obligeait à le découper autant de fois (demandé le
 * 04/10/2026).</p>
 *
 * <p>La règle vient du terrain : un bordereau vaut la somme de plusieurs
 * références du fichier, et les références d'un bordereau sont datées au
 * plus tard du jour où le camion est passé. Les bordereaux d'un même
 * délégué se remplissent donc du plus ancien au plus récent, chacun
 * prenant les références qui le précèdent, jusqu'à son poids net.</p>
 *
 * <p>Rien n'est forcé : une référence qu'aucun bordereau ne peut
 * recevoir ressort avec sa raison, plutôt que d'être logée au plus
 * proche. Un reçu créé sur le mauvais bordereau crée une dette au
 * mauvais délégué, et le défaire coûte plus cher que le relire.</p>
 */
@ApplicationScoped
public class SntDispatchService {

    @Inject IntakeNoteRepository intakeRepo;
    @Inject IntakeNoteService intakeNotes;
    @Inject SntAccountingService accounting;
    @Inject SupplierRepository suppliers;

    public SntDispatchPreviewDto preview(SntDispatchRequestDto request) {
        return allocate(request).toPreview();
    }

    /**
     * Applique la répartition, bordereau par bordereau.
     *
     * <p>Un refus sur l'un n'annule pas les autres : tout défaire pour un
     * délégué non reconnu obligerait à recharger un fichier de plusieurs
     * centaines de lignes.</p>
     */
    public SntDispatchResultDto commit(SntDispatchRequestDto request) {
        Allocation allocation = allocate(request);
        List<SntDispatchResultDto.NoteOutcome> outcomes = new ArrayList<>();
        int accountedNotes = 0;
        int createdReceipts = 0;
        int createdMembers = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Bucket bucket : allocation.buckets) {
            if (bucket.lines.isEmpty()) {
                outcomes.add(new SntDispatchResultDto.NoteOutcome(
                        bucket.note.id, bucket.note.ref, false, 0, BigDecimal.ZERO, null,
                        Messages.msg("m.itk-dispatch-no-line", bucket.note.ref)));
                continue;
            }
            try {
                // Le magasin vient du bordereau : une sélection peut en
                // couvrir plusieurs, et les faire tous entrer au même
                // rangerait la matière là où elle n'est pas.
                UUID siteId = bucket.note.siteId != null ? bucket.note.siteId : request.siteId();
                SntCommitResultDto done = accounting.commit(bucket.note.id,
                        new SntAccountingRequestDto(request.articleId(), siteId, bucket.lines));
                accountedNotes++;
                createdReceipts += done.createdReceipts();
                createdMembers += done.createdMembers();
                totalWeight = totalWeight.add(nz(done.totalWeightKg()));
                totalAmount = totalAmount.add(nz(done.totalAmount()));
                outcomes.add(new SntDispatchResultDto.NoteOutcome(
                        bucket.note.id, bucket.note.ref, true, done.createdReceipts(),
                        done.totalWeightKg(), done.weightGapKg(), null));
            } catch (RuntimeException e) {
                outcomes.add(new SntDispatchResultDto.NoteOutcome(
                        bucket.note.id, bucket.note.ref, false, 0, BigDecimal.ZERO, null,
                        UserFacingReason.of(e, "bordereau " + bucket.note.ref)));
            }
        }

        int failed = (int) outcomes.stream().filter(o -> !o.accounted()).count();
        return new SntDispatchResultDto(accountedNotes, failed, createdReceipts, createdMembers,
                totalWeight, totalAmount, outcomes, allocation.unassigned());
    }

    // ─── La répartition ─────────────────────────────────────────────

    private Allocation allocate(SntDispatchRequestDto request) {
        if (request.intakeIds() == null || request.intakeIds().isEmpty()) {
            throw new BusinessException(Messages.msg("m.itk-dispatch-no-note"));
        }
        List<SupplierEntity> collectors = suppliers.listAll().stream()
                .filter(s -> s.collector).toList();

        List<Bucket> buckets = new ArrayList<>();
        for (UUID id : request.intakeIds()) {
            IntakeNoteEntity note = intakeNotes.loadOrFail(id);
            buckets.add(new Bucket(note));
        }
        // Du plus ancien au plus récent : un bordereau prend les
        // références qui le précèdent, et commencer par le plus récent
        // lui ferait rafler celles du précédent.
        buckets.sort(Comparator.comparing(
                (Bucket b) -> b.note.date == null ? LocalDate.MIN : b.note.date)
                .thenComparing(b -> b.note.ref == null ? "" : b.note.ref));

        // Un seul délégué sur toute la sélection : le fichier n'a pas
        // besoin de le nommer, il n'y a pas d'ambiguïté à lever.
        List<UUID> delegates = buckets.stream()
                .map(b -> b.note.delegateSupplierId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        UUID soleDelegate = delegates.size() == 1 ? delegates.get(0) : null;

        List<Line> lines = new ArrayList<>();
        for (SntLineDto raw : request.lines() == null ? List.<SntLineDto>of() : request.lines()) {
            lines.add(new Line(raw, resolveDelegate(raw, collectors)));
        }
        // Les plus anciennes d'abord, pour qu'elles tombent sur les
        // bordereaux les plus anciens.
        lines.sort(Comparator.comparing(
                (Line l) -> l.date == null ? LocalDate.MIN : l.date)
                .thenComparing(l -> l.raw.rowNumber()));

        for (Bucket bucket : buckets) {
            if (bucket.note.netWeightKg == null || bucket.note.netWeightKg.signum() <= 0) continue;
            for (Line line : lines) {
                if (line.taken || line.weight == null || line.weight.signum() <= 0) continue;
                if (line.date != null && bucket.note.date != null
                        && line.date.isAfter(bucket.note.date)) {
                    continue;
                }
                if (!matches(bucket, line, soleDelegate)) continue;
                // Tant qu'il reste de la place : la dernière référence
                // déborde souvent de quelques kilos, et la refuser pour
                // cela la laisserait orpheline alors qu'elle appartient
                // bien à ce camion. L'écart se lit sur le bordereau.
                if (bucket.weight.compareTo(bucket.note.netWeightKg) >= 0) break;
                bucket.take(line);
            }
        }

        List<SntDispatchPreviewDto.UnassignedLine> unassigned = new ArrayList<>();
        for (Line line : lines) {
            if (line.taken) continue;
            unassigned.add(new SntDispatchPreviewDto.UnassignedLine(
                    line.raw.rowNumber(), ImportFields.clean(line.raw.reference()),
                    reasonFor(line, buckets, soleDelegate)));
        }
        return new Allocation(buckets, unassigned);
    }

    /** Le bordereau peut-il recevoir cette ligne, du point de vue du délégué ? */
    private static boolean matches(Bucket bucket, Line line, UUID soleDelegate) {
        if (line.delegateId == null) {
            // Le fichier ne nomme personne : acceptable tant que la
            // sélection ne porte que sur un délégué.
            return soleDelegate != null && soleDelegate.equals(bucket.note.delegateSupplierId);
        }
        return line.delegateId.equals(bucket.note.delegateSupplierId);
    }

    /** Pourquoi cette ligne n'a trouvé aucun bordereau. */
    private static String reasonFor(Line line, List<Bucket> buckets, UUID soleDelegate) {
        if (line.weight == null || line.weight.signum() <= 0) {
            return Messages.msg("m.itk-dispatch-no-weight");
        }
        boolean sameDelegate = buckets.stream().anyMatch(b -> matches(b, line, soleDelegate));
        if (!sameDelegate) return Messages.msg("m.itk-dispatch-other-delegate");
        boolean anyDateFits = buckets.stream()
                .filter(b -> matches(b, line, soleDelegate))
                .anyMatch(b -> line.date == null || b.note.date == null
                        || !line.date.isAfter(b.note.date));
        if (!anyDateFits) return Messages.msg("m.itk-dispatch-after-every-note");
        return Messages.msg("m.itk-dispatch-notes-full");
    }

    private UUID resolveDelegate(SntLineDto raw, List<SupplierEntity> collectors) {
        String phone = ImportFields.phoneKey(raw.delegatePhone());
        if (phone != null) {
            var hit = collectors.stream()
                    .filter(s -> phone.equals(ImportFields.phoneKey(s.phone))).toList();
            if (hit.size() == 1) return hit.get(0).id;
        }
        String name = ImportFields.clean(raw.delegateName());
        if (name != null) {
            String wanted = ImportFields.normalize(name);
            var hit = collectors.stream()
                    .filter(s -> s.name != null && ImportFields.normalize(s.name).equals(wanted))
                    .toList();
            if (hit.size() == 1) return hit.get(0).id;
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Un bordereau et les lignes qui lui reviennent. */
    private static final class Bucket {
        private final IntakeNoteEntity note;
        private final List<SntLineDto> lines = new ArrayList<>();
        private BigDecimal weight = BigDecimal.ZERO;

        private Bucket(IntakeNoteEntity note) {
            this.note = note;
        }

        private void take(Line line) {
            lines.add(line.raw);
            weight = weight.add(line.weight);
            line.taken = true;
        }
    }

    /** Une ligne du fichier, une fois lue. */
    private static final class Line {
        private final SntLineDto raw;
        private final LocalDate date;
        private final BigDecimal weight;
        private final UUID delegateId;
        private boolean taken;

        private Line(SntLineDto raw, UUID delegateId) {
            this.raw = raw;
            this.date = ImportFields.parseDate(raw.date());
            this.weight = ImportFields.parseDecimal(raw.weightKg());
            this.delegateId = delegateId;
        }
    }

    /** La répartition complète, avant application. */
    private record Allocation(List<Bucket> buckets,
                              List<SntDispatchPreviewDto.UnassignedLine> unassigned) {

        private SntDispatchPreviewDto toPreview() {
            Map<UUID, String> delegateNames = new LinkedHashMap<>();
            List<SntDispatchPreviewDto.NoteAllocation> notes = new ArrayList<>();
            BigDecimal assigned = BigDecimal.ZERO;
            for (Bucket b : buckets) {
                assigned = assigned.add(b.weight);
                notes.add(new SntDispatchPreviewDto.NoteAllocation(
                        b.note.id, b.note.ref, b.note.date,
                        b.note.delegateSupplierId,
                        delegateNames.getOrDefault(b.note.delegateSupplierId, b.note.supplierName),
                        b.note.netWeightKg, b.weight,
                        b.note.netWeightKg == null ? null : b.note.netWeightKg.subtract(b.weight),
                        b.lines.stream().map(SntLineDto::rowNumber).toList()));
            }
            return new SntDispatchPreviewDto(notes, unassigned, assigned);
        }
    }
}
