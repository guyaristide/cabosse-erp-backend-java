package com.ntech.cabosse.plan.service;

import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ErrorCode;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * Application des plafonds du plan tarifaire (backlog SAAS-02).
 *
 * <p>Le modèle économique repose sur des paliers : tant de comptes, tant
 * de producteurs. Un plafond que rien n'applique n'est pas un palier,
 * c'est une ligne de contrat que la plateforme ne sait pas tenir. Le
 * contrôle vit ici, au moment où la capacité se consomme, et le refus
 * nomme le plafond et la voie de sortie.</p>
 *
 * <p>Un plafond absent ou nul signifie « non contraint » : les plans semés
 * avant l'introduction d'un plafond ne bloquent rien tant que l'éditeur ne
 * les a pas dotés d'une valeur.</p>
 */
@ApplicationScoped
public class PlanLimitService {

    @Inject PlanRepository plans;
    @Inject UserRepository users;
    @Inject MemberRepository members;
    @Inject TenantContext tenantContext;
    @Inject com.ntech.cabosse.tenant.repository.TenantRepository tenants;

    /**
     * Refuse l'invitation d'un compte au-delà du plafond du plan.
     *
     * <p>Les comptes désactivés ne consomment pas de siège : désactiver un
     * ancien collaborateur doit libérer la place du suivant.</p>
     */
    public void enforceUserSeat(TenantEntity tenant) {
        PlanEntity plan = planOf(tenant);
        if (plan == null || plan.maxUsers <= 0) return;
        long active = users.countActiveByTenant(tenant.id);
        if (active >= plan.maxUsers) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT,
                    "Plafond du plan atteint : " + plan.maxUsers + " comptes utilisateurs ("
                            + active + " actifs ou invités). Désactivez un compte ou demandez "
                            + "le passage au palier supérieur.");
        }
    }

    /**
     * Refuse l'ajout de producteurs membres au-delà du plafond du plan.
     *
     * @param toAdd nombre de producteurs que l'opération va créer : 1 pour
     *              une saisie, la taille du fichier pour un import, afin
     *              qu'un import ne s'arrête pas au milieu
     */
    public void enforceMemberCapacity(int toAdd) {
        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        PlanEntity plan = planOf(tenant);
        if (plan == null || plan.maxMembers <= 0) return;
        long current = members.count();
        if (current + toAdd > plan.maxMembers) {
            throw new BusinessException(ErrorCode.PLAN_LIMIT,
                    "Plafond du plan atteint : " + plan.maxMembers + " producteurs membres ("
                            + current + " enregistrés, " + toAdd + " à créer). Demandez le "
                            + "passage au palier supérieur avant d'enregistrer de nouveaux producteurs.");
        }
    }

    /** Consommation courante face aux plafonds, pour le back-office. */
    public Usage usageOf(TenantEntity tenant) {
        PlanEntity plan = planOf(tenant);
        long activeUsers = users.countActiveByTenant(tenant.id);
        return new Usage(
                activeUsers,
                plan != null ? plan.maxUsers : 0,
                plan != null ? plan.maxMembers : 0);
    }

    private PlanEntity planOf(TenantEntity tenant) {
        if (tenant == null || tenant.planCode == null) return null;
        return plans.findByCode(tenant.planCode).orElse(null);
    }

    /** Consommation d'un tenant. Un plafond nul signifie « non contraint ». */
    public record Usage(long activeUsers, int maxUsers, int maxMembers) {}
}
