package com.ntech.cabosse.diagnostics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Une vérification de cohérence et ce qu'elle a trouvé. */
@Schema(description = "Contrôle de cohérence sur la base d'un tenant")
public record ConsistencyCheckDto(
        /** Code stable, que l'écran traduit. */
        String code,
        /** Nombre d'anomalies, zéro compris. */
        long anomalies,
        /** Les premières, pour savoir où regarder. */
        List<String> samples
) {
}
