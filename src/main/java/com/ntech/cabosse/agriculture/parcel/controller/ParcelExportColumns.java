package com.ntech.cabosse.agriculture.parcel.controller;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Colonnes de l'export du parcellaire.
 *
 * <p>Alignées sur le modèle d'import : un fichier exporté, corrigé puis
 * réimporté doit passer sans renommage. C'est le circuit réel des
 * recensements : on sort la liste, on la fait corriger sur le terrain, on
 * la recharge. D'où les libellés de statut en français (l'import ne connaît
 * pas les valeurs techniques) et le code producteur, seul rapprochement
 * fiable en présence d'homonymes.</p>
 */
final class ParcelExportColumns {

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ParcelExportColumns() {}

    /** @param memberCodeById code producteur par membre, résolu à l'export */
    static List<ExportColumn<ParcelResponseDto>> all(Map<UUID, String> memberCodeById) {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-parcel-code"), ParcelResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-name"), ParcelResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-producer-code"), p ->
                        p.memberId() == null ? null : memberCodeById.get(p.memberId())),
                ExportColumn.of(Messages.msg("m.imp-h-producer-name"), ParcelResponseDto::memberName),
                ExportColumn.of("surface-ha", Messages.msg("m.imp-h-parcel-surface"), ColumnKind.NUMBER_QTY, ParcelResponseDto::surfaceHa),
                ExportColumn.of("latitude", Messages.msg("m.imp-h-latitude"), ColumnKind.NUMBER_PRECISE, p ->
                        p.gpsCenter() == null || p.gpsCenter().size() < 2 ? null : p.gpsCenter().get(1)),
                ExportColumn.of("longitude", Messages.msg("m.imp-h-longitude"), ColumnKind.NUMBER_PRECISE, p ->
                        p.gpsCenter() == null || p.gpsCenter().size() < 2 ? null : p.gpsCenter().get(0)),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-crop"), ParcelResponseDto::cropCode),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-main-crop"), p -> yesNo(p.mainCrop())),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-variety"), ParcelResponseDto::variety),
                ExportColumn.of(Messages.msg("m.imp-h-parcel-planting-date"), p ->
                        p.plantingDate() == null ? null : p.plantingDate().format(FR_DATE)),
                ExportColumn.of("planting-year", Messages.msg("m.imp-h-parcel-planting-year"), ColumnKind.NUMBER_INT, ParcelResponseDto::plantingYear),
                ExportColumn.of(Messages.msg("m.imp-h-region"), ParcelResponseDto::regionCode),
                ExportColumn.of(Messages.msg("m.imp-h-department"), ParcelResponseDto::departmentCode),
                ExportColumn.of(Messages.msg("m.imp-h-status"), p -> statusLabel(p.status())),
                ExportColumn.of(Messages.msg("m.imp-h-notes"), ParcelResponseDto::notes),
                // Ignorée au réimport, mais la liste sert aussi d'état de synthèse.
                ExportColumn.of(Messages.msg("m.imp-h-certifications"), p ->
                        p.certifications() == null ? null : String.join(", ", p.certifications())));
    }

    /**
     * Libellé de statut, dans la langue de la requête.
     *
     * <p>Ces mots repartent dans un fichier que l'utilisateur corrigera et
     * réimportera : {@code ParcelImportService.parseStatus} doit donc
     * reconnaître chacun d'eux, dans les deux langues. Les deux listes se
     * lisent ensemble.</p>
     */
    /**
     * Oui / non dans la langue de la requête.
     *
     * <p>Sans danger pour le circuit exporter, corriger, réimporter : le
     * lecteur d'import reconnaît les deux formes, « oui » comme « yes ».
     * C'est la précaution qui manquait la première fois qu'un export a été
     * traduit, et le fichier revenait alors avec une colonne muette.</p>
     */
    private static String yesNo(boolean value) {
        return Messages.msg(value ? "m.imp-v-yes" : "m.imp-v-no");
    }

    private static String statusLabel(ParcelStatus status) {
        if (status == null) return null;
        return switch (status) {
            case ACTIVE -> Messages.msg("m.imp-v-parcel-status-active");
            case FALLOW -> Messages.msg("m.imp-v-parcel-status-fallow");
            case REPLANTING -> Messages.msg("m.imp-v-parcel-status-replanting");
            case ABANDONED -> Messages.msg("m.imp-v-parcel-status-abandoned");
        };
    }
}
