package com.ntech.cabosse.agriculture.parcel.controller;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;

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
                ExportColumn.of("Code plantation",    ParcelResponseDto::code),
                ExportColumn.of("Nom de la parcelle", ParcelResponseDto::name),
                ExportColumn.of("Code producteur", p ->
                        p.memberId() == null ? null : memberCodeById.get(p.memberId())),
                ExportColumn.of("Nom du producteur",  ParcelResponseDto::memberName),
                ExportColumn.of("surface-ha", "Superficie (ha)", ColumnKind.NUMBER_QTY,    ParcelResponseDto::surfaceHa),
                ExportColumn.of("latitude", "Latitude", ColumnKind.NUMBER_PRECISE, p ->
                        p.gpsCenter() == null || p.gpsCenter().size() < 2 ? null : p.gpsCenter().get(1)),
                ExportColumn.of("longitude", "Longitude", ColumnKind.NUMBER_PRECISE, p ->
                        p.gpsCenter() == null || p.gpsCenter().size() < 2 ? null : p.gpsCenter().get(0)),
                ExportColumn.of("Culture",            ParcelResponseDto::cropCode),
                ExportColumn.of("Culture principale", p -> p.mainCrop() ? "Oui" : "Non"),
                ExportColumn.of("Variété",            ParcelResponseDto::variety),
                ExportColumn.of("Date de plantation", p ->
                        p.plantingDate() == null ? null : p.plantingDate().format(FR_DATE)),
                ExportColumn.of("Année de plantation", ParcelResponseDto::plantingYear),
                ExportColumn.of("Région",             ParcelResponseDto::regionCode),
                ExportColumn.of("Département",        ParcelResponseDto::departmentCode),
                ExportColumn.of("Statut", p -> statusLabel(p.status())),
                ExportColumn.of("Notes",              ParcelResponseDto::notes),
                // Ignorée au réimport, mais la liste sert aussi d'état de synthèse.
                ExportColumn.of("Certifications", p ->
                        p.certifications() == null ? null : String.join(", ", p.certifications())));
    }

    /** Libellés compris par {@code ParcelImportService.parseStatus}. */
    private static String statusLabel(ParcelStatus status) {
        if (status == null) return null;
        return switch (status) {
            case ACTIVE -> "En production";
            case FALLOW -> "En jachère";
            case REPLANTING -> "Replantation";
            case ABANDONED -> "Abandonnée";
        };
    }
}
