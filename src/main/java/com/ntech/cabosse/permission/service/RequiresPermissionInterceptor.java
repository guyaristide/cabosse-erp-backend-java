package com.ntech.cabosse.permission.service;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.exception.ForbiddenException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.Arrays;
import java.util.Set;

/**
 * Applique {@link RequiresPermission}.
 *
 * <p>Le contrôle est fait à chaque appel plutôt que porté par le jeton :
 * un droit retiré doit prendre effet immédiatement, sans attendre qu'une
 * session expire. Le coût est la lecture de l'utilisateur et de ses
 * profils, deux petits documents.</p>
 */
@RequiresPermission({})
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class RequiresPermissionInterceptor {

    @Inject PermissionResolver resolver;

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
            throw new ForbiddenException(message(annotation.value()));
        }
        return ctx.proceed();
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
