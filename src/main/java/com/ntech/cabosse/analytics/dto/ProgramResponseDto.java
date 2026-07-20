package com.ntech.cabosse.analytics.dto;

import com.ntech.cabosse.analytics.entity.ProgramEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Programme budgétaire du tenant, projets inclus")
public record ProgramResponseDto(
        UUID id, String code, String name, String description,
        boolean active, List<ProjectView> projects,
        Instant createdAt, Instant updatedAt
) {
    public record ProjectView(String code, String name, boolean active) {}

    public static ProgramResponseDto from(ProgramEntity e) {
        List<ProjectView> projects = e.projects == null ? List.of() : e.projects.stream()
                .map(p -> new ProjectView(p.code, p.name, p.active))
                .toList();
        return new ProgramResponseDto(
                e.id, e.code, e.name, e.description, e.active, projects,
                e.createdAt, e.updatedAt);
    }
}
