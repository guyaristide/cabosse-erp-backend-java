package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Résultat d'un import de bordereaux de réception. */
@Schema(description = "Résultat d'import de bordereaux de réception")
public record IntakeImportResultDto(
        int createdCount,
        /** Bordereaux déjà connus par leur numéro, laissés tels quels. */
        int skippedExistingCount,
        List<RejectedRow> rejectedRows,
        /**
         * Les lignes enregistrées qui n'iront pourtant pas au bout.
         *
         * <p>Un bordereau sans délégué reconnu ne pourra jamais être
         * comptabilisé : la comptabilisation refuse le lot entier. Le
         * système le sait à la seconde où il l'écrit, et se taisait
         * jusqu'au 15/09/2026 : l'opérateur découvrait le blocage des
         * jours plus tard, à l'autre bout du circuit, sans rien pour
         * relier les deux moments.</p>
         *
         * <p>Ce ne sont pas des refus : la ligne est bien créée, et rien
         * n'est perdu. C'est un avertissement à traiter tant que le
         * fichier est encore ouvert.</p>
         */
        List<WarnedRow> warnedRows,
        /**
         * Les bordereaux déjà connus, nommés.
         *
         * <p>Le compteur disait « une ligne déjà connue » sans dire
         * laquelle : l'opérateur restait devant un fichier d'une ligne
         * sans savoir laquelle chercher, et devant un carnet de quarante
         * sans la moindre piste. Le numéro et l'état du bordereau
         * existant répondent à la seule question qu'il se pose
         * (16/09/2026).</p>
         */
        List<SkippedRow> skippedRows
) {
    /** Une ligne refusée et la raison, pour corriger le fichier. */
    public record RejectedRow(int rowNumber, String reason) {}

    /** Une ligne enregistrée, et ce qui l'empêchera d'aboutir. */
    public record WarnedRow(int rowNumber, String ref, String reason) {}

    /**
     * Un bordereau que le fichier rapporte et que la base connaît déjà.
     * Son état dit s'il attend encore la comptabilisation ou s'il est
     * passé, ce qui explique pourquoi on ne le trouve pas dans la file.
     */
    public record SkippedRow(int rowNumber, String ref, String status) {}
}
