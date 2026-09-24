package com.ntech.cabosse.me.dto;

import com.ntech.cabosse.tenant.dto.UserPermissionExceptionDto;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * La liste complète des exceptions d'une personne (backlog ADM-03).
 *
 * <p>Remplacement, non ajout : l'écran envoie ce qu'il veut voir, et une
 * exception absente de la liste disparaît. Ajouter et retirer par gestes
 * séparés obligerait à deviner l'état de départ, et deux écrans ouverts
 * en même temps se contrediraient sans que personne ne le voie.</p>
 */
@Schema(description = "Exceptions de droits d'un utilisateur, liste complète")
public record SetPermissionExceptionsPayloadDto(
        List<UserPermissionExceptionDto> exceptions) {}
