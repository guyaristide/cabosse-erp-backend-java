package com.ntech.cabosse.me.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Sites de travail attribués à un utilisateur (backlog ADM-02).
 *
 * <p>Liste vide ou absente : tous les sites de la structure, ce qui est
 * le réglage de départ. Renseignée, elle borne le sélecteur de site de
 * l'intéressé et décide du site sur lequel il atterrit.</p>
 */
@Schema(description = "Sites de travail d'un utilisateur, vide pour tous")
public record AssignUserSitesPayloadDto(List<UUID> siteIds) {}
