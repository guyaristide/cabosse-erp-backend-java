package com.ntech.cabosse.permission.service;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ForbiddenException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applique {@link RequiresPermission}.
 *
 * <p>Le contrôle est fait à chaque appel plutôt que porté par le jeton :
 * un droit retiré doit prendre effet immédiatement, sans attendre qu'une
 * session expire. Le coût est la lecture de l'utilisateur et de ses
 * profils, deux petits documents.</p>
 *
 * <p>Un refus se journalise (SEC-16) : une suite de droits refusés sur un
 * même compte est le signal qu'un contrôleur veut voir, et l'écran ne le
 * montre à personne d'autre que celui qui l'a reçu.</p>
 */
@RequiresPermission({})
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class RequiresPermissionInterceptor {

    private static final Logger LOG = Logger.getLogger(RequiresPermissionInterceptor.class);

    @Inject PermissionResolver resolver;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;
    @Inject CurrentVertxRequest currentRequest;

    @AroundInvoke
    public Object check(InvocationContext ctx) throws Exception {
        RequiresPermission annotation = ctx.getMethod().getAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = ctx.getMethod().getDeclaringClass().getAnnotation(RequiresPermission.class);
        }
        if (annotation == null || annotation.value().length == 0) {
            return ctx.proceed();
        }

        Set<Permission> granted = resolver.current();
        boolean allowed = Arrays.stream(annotation.value()).anyMatch(granted::contains);
        if (!allowed) {
            recordDenied(annotation.value());
            throw new ForbiddenException(message(annotation.value()));
        }
        return ctx.proceed();
    }

    /** La trace du refus ne doit jamais changer la réponse : tout est capturé. */
    private void recordDenied(Permission[] required) {
        try {
            String code = Arrays.stream(required).map(Permission::name)
                    .collect(Collectors.joining(","));
            HttpServerRequest http = currentRequest.getCurrent() == null
                    ? null : currentRequest.getCurrent().request();
            String method = http != null ? http.method().name() : null;
            String path = http != null ? http.path() : null;
            String email;
            try { email = jwt.getName(); } catch (Exception e) { email = null; }

            audit.event(AuditEventType.PERMISSION_DENIED)
                    .actor(email, tenantContext.userId())
                    .impersonatedBy(tenantContext.impersonatedBy())
                    .target("permission", code, code)
                    .tenant(tenantContext.tenantIdOrNull(), null)
                    .description("Droit " + code + " refusé sur " + method + " " + path)
                    .payload(Map.of(
                            "permission", code,
                            "path", path == null ? "" : path,
                            "method", method == null ? "" : method))
                    .ip(clientIp(http))
                    .record();
        } catch (Exception e) {
            LOG.warnf(e, "Refus de droit non journalisé");
        }
    }

    private static String clientIp(HttpServerRequest http) {
        if (http == null) return null;
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return http.remoteAddress() != null ? http.remoteAddress().host() : null;
    }

    /**
     * Nommer le droit manquant plutôt que de renvoyer un refus muet :
     * l'administrateur du tenant doit savoir quelle case cocher.
     */
    private static String message(Permission[] required) {
        if (required.length == 1) {
            return Messages.msg("m.per-permission-required", required[0].label());
        }
        // Les guillemets appartiennent à la langue : le français encadre de
        // chevrons, l'anglais de doubles droits. Écrits ici, ils partaient
        // en chevrons dans une phrase anglaise.
        String list = Arrays.stream(required)
                .map(p -> Messages.msg("m.per-quoted-right", p.label()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        return Messages.msg("m.per-one-of-permissions-required", list);
    }
}
