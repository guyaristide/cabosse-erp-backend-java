package com.ntech.cabosse.supplier.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;

import java.util.List;

final class SupplierExportColumns {

    private SupplierExportColumns() {}

    static List<ExportColumn<SupplierResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",                SupplierResponseDto::code),
                ExportColumn.of("Nom",                 SupplierResponseDto::name),
                ExportColumn.of("Raison sociale",      SupplierResponseDto::legalName),
                ExportColumn.of("N° fiscal",           SupplierResponseDto::taxNumber),
                ExportColumn.of("Contact",             SupplierResponseDto::contactName),
                ExportColumn.of("E-mail",              SupplierResponseDto::email),
                ExportColumn.of("Téléphone",           SupplierResponseDto::phone),
                ExportColumn.of("Adresse",             SupplierResponseDto::addressLine),
                ExportColumn.of("Ville",               SupplierResponseDto::cityName),
                ExportColumn.of("Pays",                SupplierResponseDto::countryCode),
                ExportColumn.of("Conditions paiement", SupplierResponseDto::paymentTerms),
                ExportColumn.of("Actif",               SupplierResponseDto::active),
                ExportColumn.of("Notes",               SupplierResponseDto::notes)
        );
    }
}
