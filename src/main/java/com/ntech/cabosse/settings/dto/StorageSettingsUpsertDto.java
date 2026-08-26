package com.ntech.cabosse.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture stockage")
public record StorageSettingsUpsertDto(

        @NotBlank(message = "{v.provider-requis}")
        @Pattern(regexp = "LOCAL|S3|MINIO",
                message = "{v.valeur-autorisee-local-s3-minio}")
        String provider,

        /** Requis pour S3/MINIO ; ignoré pour LOCAL. */
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        /** Convention patch sensible (cf. EmailSettingsUpsertDto). */
        String secretKey,
        boolean pathStyleAccess

) {}
