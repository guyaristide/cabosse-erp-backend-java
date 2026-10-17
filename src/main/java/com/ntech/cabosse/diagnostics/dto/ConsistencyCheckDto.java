package com.ntech.cabosse.diagnostics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Une vérification de cohérence, ce qu'elle a trouvé, et ce qu'il faut
 * faire.
 *
 * <p>Un contrôle qui compte sans dire quoi faire laisse son lecteur
 * devant un nombre. Chaque contrôle porte donc deux choses de plus :
 * qui corrige, parce que la plateforme ne peut pas toucher aux données
 * d'une structure, et comment vérifier que c'est corrigé, qui est
 * toujours de repasser les contrôles et de voir le compte tomber
 * (demandé le 17/10/2026).</p>
 */
@Schema(description = "Contrôle de cohérence sur la base d'un tenant")
public record ConsistencyCheckDto(
        /** Code stable, que l'écran traduit. */
        String code,
        /** Nombre d'anomalies, zéro compris. */
        long anomalies,
        /** Les premières, pour savoir où regarder. */
        List<String> samples,
        /** Qui peut corriger : la structure, ou la plateforme. */
        Fixer fixedBy,
        /**
         * L'écran de la structure où se fait la correction, en chemin
         * relatif. Null quand il n'y en a pas un seul.
         */
        String fixPath
) {

    /** Constructeur court : un contrôle que la structure corrige elle-même. */
    public ConsistencyCheckDto(String code, long anomalies, List<String> samples) {
        this(code, anomalies, samples, Fixer.TENANT, null);
    }

    public enum Fixer {
        /** La coopérative, dans son application. */
        TENANT,
        /** L'éditeur, par une migration ou un correctif. */
        PLATFORM
    }
}
