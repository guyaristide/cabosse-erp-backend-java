package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Filet de sécurité : mappe toute exception <strong>non gérée</strong> par
 * un mapper plus spécifique vers l'enveloppe {@link ApiResponse}, pour que
 * <em>tout</em> endpoint réponde dans le même format (cf. CLAUDE.md §8.3) —
 * y compris les 500 inattendues (NPE, erreur Mongo, etc.) qui sortiraient
 * sinon en {@code application/problem+json} et casseraient le parsing front.
 *
 * <p>JAX-RS choisit toujours le mapper le plus spécifique : celui-ci ne
 * s'applique qu'aux exceptions sans mapper dédié. On préserve le statut des
 * {@link WebApplicationException} (404 de routing, 405, 406…) et on ne
 * renvoie <strong>jamais</strong> le message interne / la stacktrace au
 * client sur une 500 — uniquement un libellé générique, la cause étant
 * journalisée côté serveur.</p>
 */
@Provider
public class ThrowableExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(ThrowableExceptionMapper.class);

    @Override
    public Response toResponse(Throwable ex) {
        if (ex instanceof WebApplicationException wae) {
            int status = wae.getResponse().getStatus();
            String message = wae.getMessage() != null ? wae.getMessage()
                    : Response.Status.fromStatusCode(status) != null
                        ? Response.Status.fromStatusCode(status).getReasonPhrase()
                        : "Erreur";
            // Une erreur portée par le framework (405, 415, 404 de route…)
            // n'est pas rejouable : la requête elle-même est mal formée.
            return Response.status(status)
                    .entity(ApiResponse.error(status, message, ErrorCode.BUSINESS_RULE))
                    .build();
        }
        LOG.error("Erreur interne non gérée", ex);
        // Seule catégorie réellement rejouable : l'incident inattendu peut
        // avoir disparu à la tentative suivante.
        return Response.status(500)
                .entity(ApiResponse.error(500, "Une erreur interne est survenue.", ErrorCode.INTERNAL))
                .build();
    }
}
