package com.ntech.cabosse.importjournal.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Le fichier déposé à un import, gardé tel quel et sans limite de durée
 * (DEC-52, 30/10/2026).
 *
 * <p>Le serveur ne recevait que les lignes lues par le navigateur : rien
 * ne prouvait ce qui avait été déposé, rien ne se rejouait, et l'archive
 * d'une structure partait sans ses fichiers. Le binaire est un fichier
 * de la structure ({@code cloud_files} de sa base), donc emporté par
 * l'archive ; cette fiche le relie au domaine importé et, quand il
 * existe, au journal de l'import.</p>
 */
public class ImportFileEntity {

    public static final String COLLECTION = "import_files";

    @BsonId
    public UUID id;

    /** Le domaine importé, tel que le journal le nomme : members, articles… */
    public String domain;

    public String fileName;
    public String mimeType;
    public long sizeBytes;

    /** Le binaire, dans le registre des fichiers de la structure. */
    public UUID cloudFileId;

    /** Le journal de l'import, pour les domaines qui en tiennent un. */
    public UUID importRunId;

    public Integer rowsCreated;
    public Integer rowsRejected;

    public Instant uploadedAt;
    public String uploadedByEmail;
}
