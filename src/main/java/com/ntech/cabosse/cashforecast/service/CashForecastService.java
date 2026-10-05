package com.ntech.cabosse.cashforecast.service;

import com.ntech.cabosse.cashforecast.dto.CashForecastLineDto;
import com.ntech.cabosse.cashforecast.dto.CashForecastResponseDto;
import com.ntech.cabosse.cashforecast.dto.CashForecastUpsertDto;
import com.ntech.cabosse.cashforecast.entity.CashForecastEntity;
import com.ntech.cabosse.cashforecast.entity.CashForecastLine;
import com.ntech.cabosse.cashforecast.entity.CashForecastStatus;
import com.ntech.cabosse.cashforecast.repository.CashForecastRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ce que la structure prévoit de décaisser, et qui s'en porte garant.
 *
 * <p>Le directeur dépose ses prévisions avant le dernier jour ouvré du
 * mois en cours, le conseil se prononce avant que l'argent ne parte
 * (demandé le 03/10/2026). Valider une commande n'est pas la payer, et
 * une trésorerie se tend quand personne ne regarde le mois d'avance.</p>
 *
 * <p>Trois états et non deux : un prévisionnel déposé n'est pas encore
 * soumis. Le directeur le charge, le relit, le corrige ; le conseil ne
 * doit pas se prononcer sur un brouillon.</p>
 *
 * <p>Le prévisionnel ne crée rien et n'engage aucune écriture. C'est un
 * document de pilotage : il dit ce qu'on compte faire, et la
 * comparaison avec le réalisé se lit ailleurs. Lui faire produire des
 * dépenses ferait payer deux fois ce qui sera saisi de toute façon.</p>
 */
@ApplicationScoped
public class CashForecastService {

    @Inject CashForecastRepository repo;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    public List<CashForecastResponseDto> list() {
        return repo.listAll().stream().map(CashForecastResponseDto::from).toList();
    }

    public CashForecastResponseDto getById(UUID id) {
        return CashForecastResponseDto.from(load(id));
    }

    /**
     * Dépose le prévisionnel d'un mois, ou remplace celui qui s'y
     * trouvait.
     *
     * <p>Un seul par mois : deux laisseraient le conseil approuver l'un
     * et lire l'autre. Recharger un fichier corrigé remplace donc le
     * précédent, tant qu'il est encore au brouillon.</p>
     */
    public CashForecastResponseDto upsert(CashForecastUpsertDto p) {
        YearMonth month = parseMonth(p.month());
        var existing = repo.findByMonth(month.toString());

        CashForecastEntity e = existing.orElseGet(() -> {
            CashForecastEntity fresh = new CashForecastEntity();
            fresh.id = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch();
            fresh.month = month.toString();
            fresh.createdAt = Instant.now();
            fresh.createdByEmail = actor();
            return fresh;
        });

        if (e.status != CashForecastStatus.DRAFT && e.status != CashForecastStatus.REJECTED) {
            throw new BusinessException(Messages.msg("m.prev-not-draft", e.month));
        }
        // Un prévisionnel refusé que l'on reprend repart au brouillon :
        // le laisser « refusé » avec de nouvelles lignes montrerait au
        // conseil une décision qui ne porte plus sur ce qu'il voit.
        e.status = CashForecastStatus.DRAFT;
        e.rejectionReason = null;
        e.decidedAt = null;
        e.decidedByEmail = null;
        e.submittedAt = null;
        e.submittedByEmail = null;

        e.lines = toLines(p.lines());
        e.openingCash = p.openingCash();
        e.openingBank = p.openingBank();
        e.expectedReceipts = p.expectedReceipts();

        if (existing.isPresent()) {
            repo.replace(e);
        } else {
            repo.insert(e);
        }
        audit.event(AuditEventType.CASH_FORECAST_FILED)
                .actorEmail(actor())
                .target("cash_forecast", e.id.toString(), e.month)
                .tenant(tenantContext.tenantId(), null)
                .description("Prévisionnel de " + e.month + " déposé : "
                        + e.lines.size() + " ligne(s), " + e.totalOutflow() + " à décaisser")
                .record();
        return CashForecastResponseDto.from(e);
    }

    /** Soumet le prévisionnel au conseil. */
    public CashForecastResponseDto submit(UUID id) {
        CashForecastEntity e = load(id);
        if (e.status != CashForecastStatus.DRAFT && e.status != CashForecastStatus.REJECTED) {
            throw new BusinessException(Messages.msg("m.prev-not-draft", e.month));
        }
        if (e.lines == null || e.lines.isEmpty()) {
            throw new BusinessException(Messages.msg("m.prev-empty"));
        }
        e.status = CashForecastStatus.SUBMITTED;
        e.submittedAt = Instant.now();
        e.submittedByEmail = actor();
        e.rejectionReason = null;
        repo.replace(e);
        trace(e, AuditEventType.CASH_FORECAST_SUBMITTED, "Prévisionnel de " + e.month + " soumis");
        return CashForecastResponseDto.from(e);
    }

    public CashForecastResponseDto approve(UUID id) {
        CashForecastEntity e = decided(id);
        e.status = CashForecastStatus.APPROVED;
        repo.replace(e);
        trace(e, AuditEventType.CASH_FORECAST_APPROVED,
                "Prévisionnel de " + e.month + " approuvé : " + e.totalOutflow() + " à décaisser");
        return CashForecastResponseDto.from(e);
    }

    /**
     * Refuse le prévisionnel.
     *
     * <p>Il repasse en brouillon côté directeur plutôt que de rester
     * mort : un refus appelle une correction, et obliger à tout
     * recharger pour une ligne perdrait les autres.</p>
     */
    public CashForecastResponseDto reject(UUID id, String reason) {
        CashForecastEntity e = decided(id);
        e.status = CashForecastStatus.REJECTED;
        e.rejectionReason = reason == null || reason.isBlank() ? null : reason.trim();
        repo.replace(e);
        trace(e, AuditEventType.CASH_FORECAST_REJECTED,
                "Prévisionnel de " + e.month + " refusé"
                        + (e.rejectionReason == null ? "" : " : " + e.rejectionReason));
        return CashForecastResponseDto.from(e);
    }

    private CashForecastEntity decided(UUID id) {
        CashForecastEntity e = load(id);
        if (e.status != CashForecastStatus.SUBMITTED) {
            throw new BusinessException(Messages.msg("m.prev-not-submitted", e.month));
        }
        e.decidedAt = Instant.now();
        e.decidedByEmail = actor();
        return e;
    }

    private void trace(CashForecastEntity e, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("cash_forecast", e.id.toString(), e.month)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private CashForecastEntity load(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.prev-not-found", id)));
    }

    private static List<CashForecastLine> toLines(List<CashForecastLineDto> input) {
        List<CashForecastLine> out = new ArrayList<>();
        if (input == null) return out;
        for (CashForecastLineDto d : input) {
            // Une ligne sans montant est une ligne du modèle que
            // personne n'a remplie : le fichier du conseil les porte
            // toutes, et les garder ferait un état de trente lignes à
            // zéro.
            if (d == null || d.amount() == null || d.amount().signum() == 0) continue;
            CashForecastLine line = new CashForecastLine();
            line.account = blankToNull(d.account());
            line.accountLabel = blankToNull(d.accountLabel());
            line.detail = blankToNull(d.detail());
            line.amount = d.amount();
            line.source = normalizeSource(d.source());
            out.add(line);
        }
        return out;
    }

    /** « Banque » ou « Caisse », écrits comme le classeur les écrit. */
    private static String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        if (key.startsWith("banq") || key.startsWith("bank")) return "BANK";
        if (key.startsWith("caiss") || key.startsWith("cash") || key.startsWith("espec")) {
            return "CASH";
        }
        return null;
    }

    private static YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(Messages.msg("m.prev-month-required"));
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (Exception e) {
            throw new BusinessException(Messages.msg("m.prev-month-unreadable", raw));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String actor() {
        try {
            return jwt != null ? jwt.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
