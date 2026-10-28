package com.ntech.cabosse.members.controller;

import com.ntech.cabosse.members.dto.MemberExportRow;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Les colonnes de l'export de la liste des membres, dans l'ordre de la grille. */
final class MemberListExportColumns {
    private MemberListExportColumns() {}

    static List<ExportColumn<MemberExportRow>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),          MemberExportRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-name"),          MemberExportRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-status"),        MemberExportRow::status),
                ExportColumn.of(Messages.msg("m.exp-h-genre"),         MemberExportRow::gender),
                ExportColumn.of(Messages.msg("m.imp-h-section"),       MemberExportRow::sectionName),
                ExportColumn.of(Messages.msg("m.exp-h-village"),       MemberExportRow::village),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),         MemberExportRow::phone),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),       MemberExportRow::collector),
                ExportColumn.of(Messages.msg("m.exp-h-parcelles"),     MemberExportRow::parcelCount),
                ExportColumn.of(Messages.msg("m.exp-h-part-sociale"),  MemberExportRow::partsSocialesAmount),
                ExportColumn.of(Messages.msg("m.exp-h-adhesion"),      MemberExportRow::joinedAt));
    }
}
