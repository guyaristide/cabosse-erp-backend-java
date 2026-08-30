package com.ntech.cabosse.support.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Un message dans le fil d'un ticket.
 *
 * <p>{@code internal} marque une note que l'éditeur s'adresse à lui-même.
 * Elle ne doit jamais quitter le back-office : le filtrage se fait au
 * moment de composer la réponse destinée à la structure, et non à
 * l'affichage — une note masquée dans un écran reste lisible dans la
 * réponse de l'API.</p>
 */
public class TicketMessageEntity {

    public UUID id;
    public String body;
    public String authorName;
    public String authorEmail;
    public TicketAuthorRole authorRole;
    public boolean internal;
    public Instant createdAt;
}
