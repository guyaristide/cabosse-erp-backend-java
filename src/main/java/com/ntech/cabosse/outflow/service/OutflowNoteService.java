package com.ntech.cabosse.outflow.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.commodity.repository.CommoditySaleRepository;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.intake.repository.IntakeNoteRepository;
import com.ntech.cabosse.outflow.dto.OutflowImportResultDto;
import com.ntech.cabosse.outflow.dto.OutflowNoteDto;
import com.ntech.cabosse.outflow.dto.OutflowNoteImportRowDto;
import com.ntech.cabosse.outflow.dto.OutflowRejectedRowDto;
import com.ntech.cabosse.outflow.entity.OutflowNoteEntity;
import com.ntech.cabosse.outflow.repository.OutflowNoteRepository;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportFields;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bordereaux de sortie du carnet du magasin (épic CE-218) : import et
 * lecture. Le bordereau est un constat, au miroir de la réception : il
 * n'écrit pas le stock, la sortie comptable appartient à la vente qui
 * porte le même N° BS et le même N° chargement.
 */
@ApplicationScoped
public class OutflowNoteService {

    @Inject OutflowNoteRepository repo;
    @Inject IntakeNoteRepository intakeNotes;
    @Inject CommoditySaleRepository sales;
    @Inject CampaignRepository campaigns;
    @Inject CustomerRepository customers;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    /**
     * La liste, décorée des rapprochements du moment : la réception du
     * même N° BR, et la vente du couple N° BS + N° chargement. Calculés
     * à la lecture pour que l'ordre des imports ne compte pas.
     */
    public List<OutflowNoteDto> list() {
        List<OutflowNoteEntity> notes = repo.list();
        if (notes.isEmpty()) return List.of();

        Map<String, UUID> intakeByRef = new HashMap<>();
        for (IntakeNoteEntity n : intakeNotes.list(null)) {
            if (n.ref != null) intakeByRef.put(ImportFields.normalize(n.ref), n.id);
        }
        Map<String, CommoditySaleEntity> saleByNumbers = new HashMap<>();
        for (CommoditySaleEntity s : sales.listWithDispatchNumbers()) {
            String key = loadKey(s.logistics.dispatchNoteNumber, s.logistics.loadingNumber);
            if (key != null) saleByNumbers.putIfAbsent(key, s);
        }

        List<OutflowNoteDto> out = new ArrayList<>(notes.size());
        for (OutflowNoteEntity e : notes) {
            UUID intakeId = e.ref == null ? null : intakeByRef.get(ImportFields.normalize(e.ref));
            CommoditySaleEntity sale = saleByNumbers.get(
                    loadKey(e.dispatchNoteNumber, e.loadingNumber));
            out.add(OutflowNoteDto.from(e, intakeId,
                    sale == null ? null : sale.id, sale == null ? null : sale.ref));
        }
        return out;
    }

    /** Clé de rapprochement N° BS + N° chargement, insensible aux espaces. */
    static String loadKey(String dispatchNoteNumber, String loadingNumber) {
        String bs = ImportFields.normalize(dispatchNoteNumber);
        String load = ImportFields.normalize(loadingNumber);
        if (bs.isEmpty()) return null;
        return bs + "|" + load;
    }

    /**
     * Enregistre les bordereaux du fichier. Un numéro déjà connu est
     * laissé tel quel (le carnet se réimporte sans doubler) ; une ligne
     * sans numéro, sans date ou sans poids net est refusée avec sa
     * raison, pour corriger le fichier plutôt que deviner.
     */
    public OutflowImportResultDto importCommit(List<OutflowNoteImportRowDto> rows, UUID siteId) {
        List<CampaignEntity> allCampaigns = campaigns.listAll();
        List<CustomerEntity> allCustomers = customers.listAll();

        int created = 0;
        int skipped = 0;
        List<OutflowRejectedRowDto> rejected = new ArrayList<>();
        for (OutflowNoteImportRowDto raw : rows == null ? List.<OutflowNoteImportRowDto>of() : rows) {
            String ref = ImportFields.clean(raw.ref());
            if (ref == null) {
                rejected.add(new OutflowRejectedRowDto(
                        raw.rowNumber(), Messages.msg("m.itk-ref-required")));
                continue;
            }
            LocalDate date = ImportFields.parseDate(raw.date());
            if (date == null) {
                rejected.add(new OutflowRejectedRowDto(
                        raw.rowNumber(), Messages.msg("m.itk-date-required")));
                continue;
            }
            BigDecimal net = ImportFields.parseDecimal(raw.netWeightKg());
            if (net == null || net.signum() <= 0) {
                rejected.add(new OutflowRejectedRowDto(
                        raw.rowNumber(), Messages.msg("m.itk-net-weight-required")));
                continue;
            }
            if (repo.findByRef(ref).isPresent()) {
                skipped++;
                continue;
            }

            OutflowNoteEntity e = new OutflowNoteEntity();
            e.id = idGenerator.newId();
            e.ref = ref;
            e.date = date;
            e.movement = ImportFields.clean(raw.movement());
            e.campaignLabel = ImportFields.clean(raw.campaignLabel());
            e.campaignId = matchCampaign(e.campaignLabel, allCampaigns);
            e.productLabel = ImportFields.clean(raw.productLabel());
            e.dispatchNoteNumber = ImportFields.clean(raw.dispatchNoteNumber());
            e.loadingNumber = ImportFields.clean(raw.loadingNumber());
            e.truckNumber = ImportFields.clean(raw.truckNumber());
            e.destination = ImportFields.clean(raw.destination());
            e.customerCode = ImportFields.clean(raw.customerCode());
            e.customerName = ImportFields.clean(raw.customerName());
            e.customerId = matchCustomer(e.customerCode, e.customerName, allCustomers);
            e.lineNumber = ImportFields.parseInt(raw.lineNumber());
            e.grossWeightKg = ImportFields.parseDecimal(raw.grossWeightKg());
            e.bagCount = ImportFields.parseInt(raw.bagCount());
            e.netWeightKg = net;
            e.siteId = siteId;
            e.createdAt = Instant.now();
            e.createdByEmail = actor();
            e.updatedAt = e.createdAt;
            repo.insert(e);
            created++;
        }
        return new OutflowImportResultDto(created, skipped, rejected);
    }

    private UUID matchCampaign(String label, List<CampaignEntity> all) {
        if (label == null) return null;
        String wanted = ImportFields.normalize(label);
        return all.stream()
                .filter(c -> c.label != null && ImportFields.normalize(c.label).equals(wanted))
                .map(c -> c.id)
                .findFirst().orElse(null);
    }

    private UUID matchCustomer(String code, String name, List<CustomerEntity> all) {
        if (code != null) {
            for (CustomerEntity c : all) {
                if (code.equalsIgnoreCase(ImportFields.clean(c.code))) return c.id;
            }
        }
        if (name != null) {
            String wanted = ImportFields.normalize(name);
            for (CustomerEntity c : all) {
                if (c.name != null && ImportFields.normalize(c.name).equals(wanted)) return c.id;
            }
        }
        return null;
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
