package com.ntech.cabosse.supplier.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;

import java.util.List;

final class SupplierExportColumns {

    private SupplierExportColumns() {}

    static List<ExportColumn<SupplierResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),                SupplierResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),                 SupplierResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-legal-name"),      SupplierResponseDto::legalName),
                ExportColumn.of(Messages.msg("m.imp-h-tax-number"),           SupplierResponseDto::taxNumber),
                ExportColumn.of(Messages.msg("m.imp-h-contact"),             SupplierResponseDto::contactName),
                ExportColumn.of(Messages.msg("m.imp-h-email"),              SupplierResponseDto::email),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),           SupplierResponseDto::phone),
                ExportColumn.of(Messages.msg("m.imp-h-address"),             SupplierResponseDto::addressLine),
                ExportColumn.of(Messages.msg("m.imp-h-city"),               SupplierResponseDto::cityName),
                ExportColumn.of(Messages.msg("m.imp-h-country"),                SupplierResponseDto::countryCode),
                ExportColumn.of(Messages.msg("m.imp-h-supplier-payment-terms"), SupplierResponseDto::paymentTerms),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),               SupplierResponseDto::active),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),               SupplierResponseDto::notes)
        );
    }
}
