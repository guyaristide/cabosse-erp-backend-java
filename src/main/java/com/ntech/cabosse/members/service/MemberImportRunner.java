package com.ntech.cabosse.members.service;

import com.ntech.cabosse.importjournal.repository.ImportRunRepository;
import com.ntech.cabosse.members.dto.MemberImportRowDto;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantAwareExecutor;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lance un import de producteurs en tâche de fond et rend la main.
 *
 * <p>Un fichier de quatre mille lignes met plusieurs minutes à s'écrire,
 * et le serveur d'entrée coupe la requête bien avant. L'écran recevait
 * alors une erreur pendant que le traitement continuait tout seul : on
 * n'atteignait jamais le résultat, et recliquer lançait un second import
 * par-dessus le premier, les deux se disputant les mêmes codes (relevé le
 * 30/09/2026).</p>
 *
 * <p>L'appel crée donc la trace, rend son identifiant tout de suite, et
 * le traitement se poursuit derrière. L'écran suit l'avancement sur cette
 * trace : c'est elle qui porte le compte rendu, ce qui la rend lisible
 * même si personne ne regarde.</p>
 *
 * <p>Un seul thread : deux imports simultanés sur la même structure se
 * disputeraient les codes producteur, et l'un des deux échouerait ligne
 * à ligne sans que rien n'en explique la cause.</p>
 */
@ApplicationScoped
public class MemberImportRunner {

    private static final Logger LOG = Logger.getLogger(MemberImportRunner.class);

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "member-import");
        t.setDaemon(true);
        return t;
    });

    @Inject MemberImportService importService;
    @Inject ImportRunRepository runs;
    @Inject com.ntech.cabosse.importjournal.service.ImportRunStarter starter;
    @Inject TenantAwareExecutor executor;
    @Inject TenantContext tenantContext;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    /**
     * Enregistre l'import comme démarré et le lance derrière.
     *
     * <p>La trace naît avant le premier écrit : si le serveur tombe en
     * cours de route, elle reste en cours et dit qu'un import a commencé,
     * ce qu'aucune autre trace ne dirait.</p>
     */
    public UUID start(List<MemberImportRowDto> rows, boolean includeWarnings) {
        UUID runId = idGenerator.newId();
        UUID tenantId = tenantContext.tenantId();
        String databaseName = tenantContext.databaseName();
        UUID userId = tenantContext.userId();
        Set<String> roles = tenantContext.roles();
        String actor = jwt == null ? null : jwt.getClaim("email");

        runs.insert(starter.starting(
                runId, "members", rows == null ? 0 : rows.size(), actor, null));

        worker.submit(() -> executor.runAs(tenantId, databaseName, userId, roles, () -> {
            try {
                importService.commitInto(runId, rows, includeWarnings);
            } catch (RuntimeException e) {
                // Un import qui tombe doit le dire : sans cela l'écran
                // attendrait un résultat qui n'arrivera jamais.
                LOG.errorf(e, "Import de producteurs %s interrompu", runId);
                runs.findById(runId).ifPresent(run -> {
                    run.status = "FAILED";
                    run.failure = e.getMessage();
                    runs.replace(run);
                });
            }
        }));
        return runId;
    }
}
