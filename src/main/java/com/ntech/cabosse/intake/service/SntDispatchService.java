package com.ntech.cabosse.intake.service;

import com.ntech.cabosse.intake.dto.SntAccountingRequestDto;
import com.ntech.cabosse.intake.dto.SntCommitResultDto;
import com.ntech.cabosse.intake.dto.SntDispatchPreviewDto;
import com.ntech.cabosse.intake.dto.SntDispatchRequestDto;
import com.ntech.cabosse.intake.dto.SntDispatchResultDto;
import com.ntech.cabosse.intake.dto.SntLineDto;
import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.intake.repository.IntakeNoteRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportFields;
import com.ntech.cabosse.shared.imports.PartyMatch;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

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
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

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
        // Chaque bordereau comptabilisé a déjà sa ligne ; celle-ci résume
        // la répartition dans son ensemble.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notesSelected", allocation.buckets.size());
        payload.put("notesAccounted", accountedNotes);
        payload.put("notesFailed", failed);
        payload.put("receiptsCreated", createdReceipts);
        payload.put("membersCreated", createdMembers);
        payload.put("weightKg", totalWeight);
        payload.put("amount", totalAmount);
        payload.put("unassignedLines", allocation.unassigned().size());
        payload.put("noteRefs", allocation.buckets.stream().map(b -> b.note.ref).toList());
        audit.event(AuditEventType.INTAKE_NOTE_DISPATCHED)
                .actorEmail(actor())
                .target("intake_note", null, accountedNotes + " bordereau(x)")
                .tenant(tenantContext.tenantId(), null)
                .description("Extrait réparti sur " + allocation.buckets.size() + " bordereau(x) : "
                        + accountedNotes + " comptabilisé(s), " + failed + " refusé(s), "
                        + createdReceipts + " reçu(s) créé(s)")
                .payload(payload)
                .record();
        return new SntDispatchResultDto(accountedNotes, failed, createdReceipts, createdMembers,
                totalWeight, totalAmount, outcomes, allocation.unassigned());
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    // ─── La répartition ─────────────────────────────────────────────

    private Allocation allocate(SntDispatchRequestDto request) {
        if (request.intakeIds() == null || request.intakeIds().isEmpty()) {
            throw new BusinessException(Messages.msg("m.itk-dispatch-no-note"));
        }
        // Tous les fournisseurs, et non les seuls délégués : depuis le
        // 13/10/2026 un bordereau peut venir d'un producteur membre ou
        // non membre, et le restreindre aux délégués rendait son
        // apporteur introuvable (expert, 16/10/2026).
        List<SupplierEntity> parties = suppliers.listAll();

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

        // Un seul apporteur sur toute la sélection : le fichier n'a pas
        // besoin de le nommer, il n'y a pas d'ambiguïté à lever.
        List<UUID> counterparties = buckets.stream()
                .map(Bucket::counterpartyId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        UUID soleDelegate = counterparties.size() == 1 ? counterparties.get(0) : null;

        List<Line> lines = new ArrayList<>();
        for (SntLineDto raw : request.lines() == null ? List.<SntLineDto>of() : request.lines()) {
            lines.add(new Line(raw, PartyMatch.of(raw.delegateName(), raw.delegatePhone(), parties)));
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

    /** Le bordereau peut-il recevoir cette ligne, du point de vue de l'apporteur ? */
    private static boolean matches(Bucket bucket, Line line, UUID soleDelegate) {
        if (line.party.id() == null) {
            // Le fichier ne nomme personne de reconnu : acceptable tant
            // que la sélection ne porte que sur un apporteur.
            return soleDelegate != null && soleDelegate.equals(bucket.counterpartyId());
        }
        return line.party.id().equals(bucket.counterpartyId());
    }

    /**
     * Pourquoi cette ligne n'a trouvé aucun bordereau.
     *
     * <p>La raison dit ce qui s'est réellement passé, et non ce qu'on en
     * déduisait : un nom que le registre ne connaît pas et un nom connu
     * dont aucun bordereau n'est retenu appellent deux gestes différents,
     * et les confondre laissait le comptable chercher (signalé le
     * 16/10/2026 sur le bordereau 0896).</p>
     */
    private static String reasonFor(Line line, List<Bucket> buckets, UUID soleDelegate) {
        if (line.weight == null || line.weight.signum() <= 0) {
            return Messages.msg("m.itk-dispatch-no-weight");
        }
        if (line.party.id() == null && line.party.named() != null) {
            return line.party.ambiguous()
                    ? Messages.msg("m.itk-dispatch-party-ambiguous", line.party.named())
                    : Messages.msg("m.itk-dispatch-party-unknown", line.party.named());
        }
        if (line.party.id() == null && soleDelegate == null) {
            return Messages.msg("m.itk-dispatch-party-missing");
        }
        boolean samePartyNote = buckets.stream().anyMatch(b -> matches(b, line, soleDelegate));
        if (!samePartyNote) {
            return Messages.msg("m.itk-dispatch-other-delegate",
                    line.party.named() == null ? "" : line.party.named());
        }
        boolean anyDateFits = buckets.stream()
                .filter(b -> matches(b, line, soleDelegate))
                .anyMatch(b -> line.date == null || b.note.date == null
                        || !line.date.isAfter(b.note.date));
        if (!anyDateFits) return Messages.msg("m.itk-dispatch-after-every-note");
        return Messages.msg("m.itk-dispatch-notes-full");
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

        /**
         * À qui ce bordereau est au nom : son délégué quand il en a un,
         * sinon le fournisseur ordinaire qui a apporté la matière.
         */
        private UUID counterpartyId() {
            return note.delegateSupplierId != null ? note.delegateSupplierId : note.supplierId;
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
        private final PartyMatch party;
        private boolean taken;

        private Line(SntLineDto raw, PartyMatch party) {
            this.raw = raw;
            this.date = ImportFields.parseDate(raw.date());
            this.weight = ImportFields.parseDecimal(raw.weightKg());
            this.party = party;
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
