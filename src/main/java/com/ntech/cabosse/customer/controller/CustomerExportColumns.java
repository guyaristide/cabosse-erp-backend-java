package com.ntech.cabosse.customer.controller;

import com.ntech.cabosse.customer.dto.CustomerResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;

import java.util.List;

final class CustomerExportColumns {

    private CustomerExportColumns() {}

    static List<ExportColumn<CustomerResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",           CustomerResponseDto::code),
                ExportColumn.of("Nom",            CustomerResponseDto::name),
                ExportColumn.of("Type",           c -> humanType(c.type())),
                ExportColumn.of("Canal",          c -> humanChannel(c.channelType())),
                ExportColumn.of("Raison sociale", CustomerResponseDto::legalName),
                ExportColumn.of("N° fiscal",      CustomerResponseDto::taxNumber),
                ExportColumn.of("Contact",        CustomerResponseDto::contactName),
                ExportColumn.of("E-mail",         CustomerResponseDto::email),
                ExportColumn.of("Téléphone",      CustomerResponseDto::phone),
                ExportColumn.of("Adresse",        CustomerResponseDto::addressLine),
                ExportColumn.of("Ville",          CustomerResponseDto::cityName),
                ExportColumn.of("Pays",           CustomerResponseDto::countryCode),
                ExportColumn.of("Plafond crédit", CustomerResponseDto::creditLimit),
                ExportColumn.of("Actif",          CustomerResponseDto::active),
                ExportColumn.of("Notes",          CustomerResponseDto::notes)
        );
    }

    private static String humanType(String code) {
        if (code == null) return "";
        return switch (code) {
            case "INDIVIDUAL" -> "Particulier";
            case "COMPANY"    -> "Entreprise";
            default           -> code;
        };
    }

    private static String humanChannel(String code) {
        if (code == null) return "";
        return switch (code) {
            case "GMS"        -> "GMS";
            case "HOTELLERIE" -> "Hôtellerie";
            case "B2B"        -> "B2B";
            case "B2C"        -> "B2C";
            case "RETAIL"     -> "Retail";
            case "OTHER"      -> "Autre";
            default           -> code;
        };
    }
}
