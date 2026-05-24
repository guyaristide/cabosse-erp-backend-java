package com.ntech.cabosse.settings.repository;

import com.ntech.cabosse.settings.entity.PlatformSettingEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class PlatformSettingsRepository
        implements PanacheMongoRepositoryBase<PlatformSettingEntity, String> {

    public Optional<PlatformSettingEntity> findBySection(String section) {
        return Optional.ofNullable(findById(section));
    }
}
