package com.ntech.cabosse.shared.storage;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Fragments partagés par les ressources qui exposent des pièces jointes.
 *
 * <p>Deux gestes reviennent à l'identique partout : lire le binaire d'un
 * envoi multipart, et servir un fichier en téléchargement. Les recopier
 * ressource par ressource, c'est accepter qu'ils divergent.</p>
 */
public final class AttachmentEndpoints {

    private AttachmentEndpoints() {}

    /** Contenu d'un envoi multipart, ou erreur explicite si rien n'est joint. */
    public static byte[] readBytes(FileUpload upload) {
        if (upload == null || upload.size() == 0) {
            throw new BusinessException(Messages.msg("m.shr-no-file-in-request"));
        }
        try {
            return Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new BusinessException(Messages.msg("m.shr-file-read-failed", e.getMessage()), e);
        }
    }

    /** Sert une pièce en consultation, sous son nom d'origine. */
    public static Response download(AttachmentService.AttachmentStream s) {
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> {
                    try (var in = s.content()) {
                        in.transferTo(output);
                    }
                })
                .type(s.mimeType() != null ? s.mimeType() : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", s.sizeBytes())
                .header("Content-Disposition",
                        "inline; filename=\"" + (s.fileName() != null ? s.fileName() : "piece") + "\"")
                .header("Cache-Control", "private, max-age=300")
                .build();
    }
}
