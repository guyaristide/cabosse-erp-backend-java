package com.ntech.cabosse.notification.dto;

import com.ntech.cabosse.notification.engine.ProviderEnginePort;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Ce qu'un moteur dit de lui-même. L'écran d'administration dessine son
 * formulaire à partir de cette description : ajouter un moteur au backend
 * n'impose alors aucune modification du back-office.
 */
@Schema(description = "Moteur d'envoi disponible et paramètres qu'il attend")
public record EngineDescriptorDto(String code, String label,
                                  NotificationChannel channel,
                                  List<ParamDto> params) {

    @Schema(description = "Paramètre attendu par un moteur")
    public record ParamDto(String code, String label, boolean secret,
                           boolean required, String help) {}

    public static EngineDescriptorDto from(ProviderEnginePort engine) {
        return new EngineDescriptorDto(
                engine.code(),
                engine.label(),
                engine.channel(),
                engine.declaredParams().stream()
                        .map(p -> new ParamDto(p.code(), p.label(), p.secret(),
                                p.required(), p.help()))
                        .toList());
    }
}
