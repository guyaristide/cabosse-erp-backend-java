package com.ntech.cabosse.shared.security;

import java.util.Set;

/**
 * Rôles applicatifs Cabosse ERP. Les valeurs sont utilisées :
 *   - dans {@code @RolesAllowed} (annotations JAX-RS / Quarkus)
 *   - dans les claims JWT (groupe MicroProfile)
 *   - dans les vérifications runtime ({@code Set.contains}, etc.)
 *
 * Toute string littérale référençant un rôle ailleurs dans le code est interdite
 * (cf. shared-constants.md §3).
 */
public final class Roles {

    private Roles() {}

    /** Super-admin de la plateforme. Accès au back-office d'administration (M9). */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    /** Administrateur d'un tenant. Gère les utilisateurs, rôles, paramètres du tenant. */
    public static final String TENANT_ADMIN = "TENANT_ADMIN";

    /** Utilisateur standard d'un tenant. Accès limité par les rôles métier. */
    public static final String USER = "USER";

    /** Compte technique pour les jobs et événements internes. Non assignable à un humain. */
    public static final String SYSTEM = "SYSTEM";

    public static final Set<String> ALL = Set.of(
            PLATFORM_ADMIN, TENANT_ADMIN, USER, SYSTEM
    );

    /** Rôles assignables à un utilisateur humain (exclut SYSTEM). */
    public static final Set<String> HUMAN_ASSIGNABLE = Set.of(
            PLATFORM_ADMIN, TENANT_ADMIN, USER
    );

    public static boolean isKnown(String role) {
        return ALL.contains(role);
    }
}
