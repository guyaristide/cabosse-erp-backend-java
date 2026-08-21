package com.ntech.cabosse.permission.service;

import com.ntech.cabosse.permission.entity.Permission;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exige un droit précis pour exécuter la méthode (backlog ADM-01).
 *
 * <p>Se pose en plus de {@code @RolesAllowed}, qui reste la première
 * barrière. Un rôle dit d'où vient l'appelant, une permission dit ce qu'il
 * a le droit de faire : les deux se complètent au lieu de se
 * remplacer.</p>
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequiresPermission {

    /** Droits acceptés. L'un d'eux suffit. */
    @Nonbinding Permission[] value();
}
