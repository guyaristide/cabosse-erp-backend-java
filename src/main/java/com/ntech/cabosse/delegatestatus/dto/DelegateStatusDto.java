package com.ntech.cabosse.delegatestatus.dto;

import com.ntech.cabosse.delegatestatus.entity.DelegateStatusEntity;

import java.util.UUID;

/** Une position que la coopérative peut tenir sur un délégué. */
public record DelegateStatusDto(
        UUID id,
        String code,
        String label,
        boolean warning,
        int sortOrder,
        boolean active) {

    public static DelegateStatusDto from(DelegateStatusEntity e) {
        return new DelegateStatusDto(e.id, e.code, e.label, e.warning, e.sortOrder, e.active);
    }
}
