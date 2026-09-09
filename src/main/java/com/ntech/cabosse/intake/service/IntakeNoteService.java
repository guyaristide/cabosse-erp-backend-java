package com.ntech.cabosse.intake.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.intake.dto.IntakeImportResultDto;
import com.ntech.cabosse.intake.dto.IntakeNoteDto;
import com.ntech.cabosse.intake.dto.IntakeNoteImportRowDto;
import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.intake.repository.IntakeNoteRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bordereaux de réception du magasin (épic CE-218) : import du carnet,
 * lecture, et rien d'autre. Le bordereau est un constat (DEC-41) : il
 * n'écrit pas le stock, il attend sa comptabilisation.
 */
@ApplicationScoped
public class IntakeNoteService {

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Inject IntakeNoteRepository repo;
    @Inject CampaignRepository campaigns;
    @Inject com.ntech.cabosse.supplier.repository.SupplierRepository suppliers;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    public List<IntakeNoteDto> list(String status) {
        return repo.list(status).stream().map(IntakeNoteDto::from).toList();
    }

    public IntakeNoteEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.itk-note-not-found")));
    }

    public IntakeNoteDto getById(UUID id) {
        return IntakeNoteDto.from(loadOrFail(id));
    }

    /**
     * Enregistre les bordereaux du fichier. Un numéro déjà connu est
     * laissé tel quel (le carnet se réimporte sans doubler) ; une ligne
     * sans numéro, sans date ou sans poids net est refusée avec sa
     * raison, pour corriger le fichier plutôt que deviner.
     */
    public IntakeImportResultDto importCommit(List<IntakeNoteImportRowDto> rows, UUID siteId) {
        List<CampaignEntity> allCampaigns = campaigns.listAll();
        List<SupplierEntity> collectors = suppliers.listAll().stream()
                .filter(s -> s.collector).toList();

        int created = 0;
        int skipped = 0;
        List<IntakeImportResultDto.RejectedRow> rejected = new ArrayList<>();
        for (IntakeNoteImportRowDto raw : rows == null ? List.<IntakeNoteImportRowDto>of() : rows) {
            String ref = clean(raw.ref());
            if (ref == null) {
                rejected.add(new IntakeImportResultDto.RejectedRow(
                        raw.rowNumber(), Messages.msg("m.itk-ref-required")));
                continue;
            }
            LocalDate date = parseDate(raw.date());
            if (date == null) {
                rejected.add(new IntakeImportResultDto.RejectedRow(
                        raw.rowNumber(), Messages.msg("m.itk-date-required")));
                continue;
            }
            BigDecimal net = parseDecimal(raw.netWeightKg());
            if (net == null || net.signum() <= 0) {
                rejected.add(new IntakeImportResultDto.RejectedRow(
                        raw.rowNumber(), Messages.msg("m.itk-net-weight-required")));
                continue;
            }
            if (repo.findByRef(ref).isPresent()) {
                skipped++;
                continue;
            }

            IntakeNoteEntity e = new IntakeNoteEntity();
            e.id = idGenerator.newId();
            e.ref = ref;
            e.date = date;
            e.movement = clean(raw.movement());
            e.campaignLabel = clean(raw.campaignLabel());
            e.campaignId = matchCampaign(e.campaignLabel, allCampaigns);
            e.productLabel = clean(raw.productLabel());
            e.truckNumber = clean(raw.truckNumber());
            e.supplierCode = clean(raw.supplierCode());
            e.supplierName = clean(raw.supplierName());
            e.delegateSupplierId = matchDelegate(e.supplierCode, e.supplierName, collectors);
            e.lineNumber = parseInt(raw.lineNumber());
            e.grossWeightKg = parseDecimal(raw.grossWeightKg());
            e.bagCount = parseInt(raw.bagCount());
            e.netWeightKg = net;
            e.siteId = siteId;
            e.createdAt = Instant.now();
            e.createdByEmail = actor();
            e.updatedAt = e.createdAt;
            repo.insert(e);
            created++;
        }
        return new IntakeImportResultDto(created, skipped, rejected);
    }

    private UUID matchCampaign(String label, List<CampaignEntity> all) {
        if (label == null) return null;
        String wanted = normalize(label);
        return all.stream()
                .filter(c -> c.label != null && normalize(c.label).equals(wanted))
                .map(c -> c.id)
                .findFirst().orElse(null);
    }

    private UUID matchDelegate(String code, String name, List<SupplierEntity> collectors) {
        if (code != null) {
            for (SupplierEntity s : collectors) {
                if (code.equalsIgnoreCase(clean(s.code))) return s.id;
            }
        }
        if (name != null) {
            String wanted = normalize(name);
            for (SupplierEntity s : collectors) {
                if (s.name != null && normalize(s.name).equals(wanted)) return s.id;
            }
        }
        return null;
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    // ─── Normalisation des champs du fichier ───
    // Les extraits copiés du web charrient l'espace insécable (160) et la
    // fine (8239) : elles se traitent comme l'espace ordinaire, partout.

    static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replace('\u00A0', ' ').replace('\u202F', ' ')
                .replaceAll("[\\s\\u00A0\\u202F]+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    static String normalize(String raw) {
        String s = clean(raw);
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    static LocalDate parseDate(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        try { return LocalDate.parse(s, FR_DATE); } catch (Exception ignored) { }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        } catch (Exception ignored) { }
        try {
            // Année sur deux chiffres, telle qu'un classeur peut la garder.
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yy"));
        } catch (Exception ignored) { }
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (Exception ignored) { }
        return null;
    }

    static BigDecimal parseDecimal(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(" ", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parseInt(String raw) {
        BigDecimal d = parseDecimal(raw);
        return d == null ? null : d.intValue();
    }

    /** Les 8 derniers chiffres d'un téléphone, comme le contrôle doublons. */
    static String phoneKey(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 8) return digits.isEmpty() ? null : digits;
        return digits.substring(digits.length() - 8);
    }
}
