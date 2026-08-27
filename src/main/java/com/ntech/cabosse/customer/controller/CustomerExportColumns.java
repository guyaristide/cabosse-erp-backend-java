package com.ntech.cabosse.customer.controller;

import com.ntech.cabosse.customer.dto.CustomerResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

final class CustomerExportColumns {

    private CustomerExportColumns() {}

    static List<ExportColumn<CustomerResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),           CustomerResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),            CustomerResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-type"),           c -> humanType(c.type())),
                ExportColumn.of(Messages.msg("m.imp-h-canal"),          c -> humanChannel(c.channelType())),
                ExportColumn.of(Messages.msg("m.imp-h-legal-name"), CustomerResponseDto::legalName),
                ExportColumn.of(Messages.msg("m.imp-h-tax-number"),      CustomerResponseDto::taxNumber),
                ExportColumn.of(Messages.msg("m.imp-h-contact"),        CustomerResponseDto::contactName),
                ExportColumn.of(Messages.msg("m.imp-h-email"),         CustomerResponseDto::email),
                ExportColumn.of(Messages.msg("m.imp-h-phone"),      CustomerResponseDto::phone),
                ExportColumn.of(Messages.msg("m.imp-h-address"),        CustomerResponseDto::addressLine),
                ExportColumn.of(Messages.msg("m.imp-h-city"),          CustomerResponseDto::cityName),
                ExportColumn.of(Messages.msg("m.imp-h-country"),           CustomerResponseDto::countryCode),
                ExportColumn.of(Messages.msg("m.imp-h-customer-credit-limit"), CustomerResponseDto::creditLimit),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),          CustomerResponseDto::active),
                ExportColumn.of(Messages.msg("m.imp-h-notes"),          CustomerResponseDto::notes)
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
