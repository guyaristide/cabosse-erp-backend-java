package com.ntech.cabosse.permission.controller;

import com.ntech.cabosse.permission.dto.TenantRoleExportRow;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export des profils et de leurs droits. */
final class TenantRoleExportColumns {

    private TenantRoleExportColumns() {}

    static List<ExportColumn<TenantRoleExportRow>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-profil"),        TenantRoleExportRow::profileName),
                ExportColumn.of(Messages.msg("m.imp-h-code"),          TenantRoleExportRow::profileCode),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),         TenantRoleExportRow::active),
                ExportColumn.of(Messages.msg("m.imp-h-utilisateurs"),  TenantRoleExportRow::userCount),
                ExportColumn.of(Messages.msg("m.imp-h-droit"),         TenantRoleExportRow::permissionCode),
                ExportColumn.of(Messages.msg("m.imp-h-droit-libelle"), TenantRoleExportRow::permissionLabel),
                ExportColumn.of(Messages.msg("m.imp-h-inoperant"),     TenantRoleExportRow::inactive));
    }
}
