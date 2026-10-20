package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.dto.ChartAccountUpsertDto;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Le plan comptable devient éditable par le tenant (backlog CE-66).
 *
 * <p>Il était semé et étendu par migrations : ouvrir une deuxième caisse
 * ou une deuxième banque demandait une livraison, alors qu'une coopérative
 * à plusieurs sites en a besoin dès le premier jour. La règle « une caisse
 * ne se garnit que depuis une banque » n'a d'ailleurs de sens que si la
 * structure peut déclarer ses caisses.</p>
 *
 * <p>Deux gardes tiennent l'intégrité du grand livre. Le <strong>numéro
 * ne se change pas</strong> : il est l'identité du compte dans toutes les
 * écritures déjà passées, et le renuméroter les orphelinerait en silence.
 * Un compte <strong>ne se supprime pas</strong>, il se désactive : un
 * exercice antérieur doit rester lisible.</p>
 */
@ApplicationScoped
public class ChartOfAccountsService {

    @Inject ChartOfAccountsRepository chart;
    @Inject JournalPieceRepository pieces;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    /**
     * Le numéro qu'aurait le prochain sous-compte d'une racine.
     *
     * <p>Le comptable ouvrait le compte nominatif d'un délégué ou d'un
     * producteur en allant chercher au plan le dernier numéro posé sous
     * la racine pour taper le suivant (demandé le 19/10/2026). La racine
     * dit où l'on est, le plan dit où l'on en est.</p>
     *
     * <p>La racine se lit comme le reste du plan : sa partie significative
     * est le numéro sans ses zéros de fin, et les positions libérées par
     * ces zéros portent le rang. Sous {@code 409100} les sous-comptes vont
     * de {@code 409101} à {@code 409199} ; sous {@code 4091100000}, de
     * {@code 4091100001} à {@code 4091199999}. Le rang suivant est le plus
     * grand déjà posé plus un, un numéro ouvert à la main et laissé entre
     * deux n'est jamais repris. Quand les positions sont toutes prises, le
     * numéro s'allonge d'un rang.</p>
     */
    public String nextSubAccountNumber(String rootNumber) {
        String root = rootNumber == null ? "" : rootNumber.trim();
        if (!chart.numberExists(root)) {
            throw new BusinessException(Messages.msg("m.acc-chart-account-not-found", root));
        }
        return SubAccountNumbering.next(root,
                new java.util.HashSet<>(chart.numbersStartingWith(SubAccountNumbering.prefixOf(root))))
                .orElseThrow(() -> new BusinessException(
                        Messages.msg("m.acc-chart-sub-account-exhausted", root)));
    }

    /**
     * Ouvre le prochain sous-compte d'une racine, avec l'intitulé donné.
     *
     * <p>Deux saisies en même temps peuvent viser le même numéro : l'index
     * unique en refuse une, qui recommence avec le numéro recalculé.</p>
     */
    public ChartOfAccountsEntity createSubAccount(String rootNumber, String label) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String number = nextSubAccountNumber(rootNumber);
            try {
                return create(new ChartAccountUpsertDto(number, label));
            } catch (com.mongodb.MongoWriteException e) {
                if (e.getError().getCategory() != com.mongodb.ErrorCategory.DUPLICATE_KEY) throw e;
            }
        }
        throw new BusinessException(Messages.msg("m.acc-chart-number-taken", rootNumber));
    }

    public ChartOfAccountsEntity create(ChartAccountUpsertDto p) {
        String number = p.number().trim();
        if (chart.numberExists(number)) {
            throw new BusinessException(Messages.msg("m.acc-chart-number-taken", number));
        }
        Instant now = Instant.now();
        ChartOfAccountsEntity e = new ChartOfAccountsEntity();
        e.id = idGenerator.newId();
        e.number = number;
        e.label = p.label().trim();
        // Déduite du numéro, jamais reçue de l'appelant : c'est le premier
        // chiffre qui dit la classe, et lui seul.
        e.family = AccountFamily.fromNumber(number);
        e.active = true;
        // Un compte ouvert par la structure n'est pas un compte du socle :
        // elle en dispose, y compris pour le désactiver.
        e.system = false;
        e.createdAt = now;
        e.updatedAt = now;
        chart.insert(e);
        trace(e, "Compte " + e.number + " (" + e.label + ") ouvert");
        return e;
    }

    /**
     * Change le libellé et la famille. Pas le numéro.
     *
     * <p>Le numéro est ce que portent les écritures : le changer les
     * détacherait de leur compte sans que rien ne le signale, et le grand
     * livre afficherait des lignes sans intitulé. Pour renuméroter, on
     * ouvre le nouveau compte et on désactive l'ancien, ce qui laisse
     * l'historique lisible.</p>
     */
    public ChartOfAccountsEntity update(UUID id, ChartAccountUpsertDto p) {
        ChartOfAccountsEntity e = load(id);
        if (!e.number.equals(p.number().trim())) {
            throw new BusinessException(Messages.msg("m.acc-chart-number-immutable", e.number));
        }
        e.label = p.label().trim();
        e.updatedAt = Instant.now();
        chart.replace(e);
        trace(e, "Compte " + e.number + " renommé « " + e.label + " »");
        return e;
    }

    /**
     * Active ou désactive un compte.
     *
     * <p>Les comptes du socle ne se désactivent pas : le moteur de
     * comptabilisation les emploie sans les choisir, et les retirer ferait
     * échouer un achat ou une vente au moment de passer l'écriture, avec
     * pour seul symptôme une opération refusée.</p>
     */
    public ChartOfAccountsEntity setActive(UUID id, boolean active) {
        ChartOfAccountsEntity e = load(id);
        if (!active && e.system) {
            throw new BusinessException(Messages.msg("m.acc-chart-system-account", e.number));
        }
        e.active = active;
        e.updatedAt = Instant.now();
        chart.replace(e);
        trace(e, "Compte " + e.number + (active ? " réactivé" : " désactivé"));
        return e;
    }

    private ChartOfAccountsEntity load(UUID id) {
        return chart.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.acc-chart-account-not-found", id)));
    }

    private void trace(ChartOfAccountsEntity e, String description) {
        audit.event(AuditEventType.CHART_ACCOUNT_CHANGED)
                .actorEmail(actor())
                .target("chart_account", e.id.toString(), e.number)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
