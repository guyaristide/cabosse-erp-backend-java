package com.ntech.cabosse.notification.dto;

import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.settings.service.SecretCipher;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vue lecture d'une passerelle configurée. Les valeurs secrètes ne sont
 * jamais rendues en clair : seulement masquées, avec un drapeau disant si
 * elles sont renseignées.
 */
@Schema(description = "Passerelle d'envoi configurée")
public record ProviderResponseDto(UUID id, String engineCode, String label,
                                  NotificationChannel channel, boolean active,
                                  boolean usable, String unusableReason,
                                  Map<String, String> params,
                                  Set<String> secretKeys,
                                  List<UsageDto> usages,
                                  Instant updatedAt, String updatedBy) {

    @Schema(description = "Usage servi et rang de préférence")
    public record UsageDto(NotificationUsage usage, int priority) {}

    public static ProviderResponseDto from(NotificationProviderEntity e,
                                            boolean usable, String unusableReason) {
        Set<String> secrets = e.secretKeys != null ? e.secretKeys : Set.of();
        Map<String, String> shown = new LinkedHashMap<>();
        if (e.params != null) {
            e.params.forEach((key, value) -> shown.put(
                    key, secrets.contains(key) ? SecretCipher.mask(value) : value));
        }
        List<UsageDto> usages = e.usages == null ? List.of()
                : e.usages.stream().map(u -> new UsageDto(u.usage, u.priority)).toList();
        return new ProviderResponseDto(e.id, e.engineCode, e.label, e.channel, e.active,
                usable, unusableReason, shown, secrets, usages, e.updatedAt, e.updatedBy);
    }
}
