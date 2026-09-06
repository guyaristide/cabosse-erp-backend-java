package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.permission.entity.Permission;

/**
 * Un événement notifiable du catalogue : son code, son libellé (clé du
 * catalogue de messages), l'audience par défaut (les porteurs d'un
 * droit), et si cette audience se configure.
 *
 * <p>{@code audienceConfigurable} est faux quand le destinataire est
 * intrinsèque à l'événement (le report d'un reliquat s'adresse à celle
 * qui l'a demandé, à personne d'autre) : la règle n'y touche pas, seuls
 * les canaux et les copies restent réglables.</p>
 */
public record NotificationEventSpec(
        String code,
        String labelKey,
        Permission defaultAudience,
        boolean audienceConfigurable
) {}
