package com.ntech.cabosse.support.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.support.dto.AssignTicketDto;
import com.ntech.cabosse.support.dto.CreateTicketDto;
import com.ntech.cabosse.support.dto.SupportTicketDto;
import com.ntech.cabosse.support.dto.TicketReplyDto;
import com.ntech.cabosse.support.entity.SupportTicketEntity;
import com.ntech.cabosse.support.entity.TicketAuthorRole;
import com.ntech.cabosse.support.entity.TicketMessageEntity;
import com.ntech.cabosse.support.entity.TicketPriority;
import com.ntech.cabosse.support.entity.TicketStatus;
import com.ntech.cabosse.support.repository.SupportTicketRepository;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le cycle de vie d'un ticket d'assistance.
 *
 * <p>Deux publics, un seul objet : la structure ouvre, suit et répond ;
 * l'éditeur affecte, priorise, fait transiter et répond. Les deux
 * chemins passent par ce service pour que la règle de transition, la
 * trace d'audit et l'avis par courriel ne dépendent pas de l'écran qui a
 * déclenché l'action.</p>
 */
@ApplicationScoped
public class SupportTicketService {

    @Inject SupportTicketRepository tickets;
    @Inject SupportTicketRefService refs;
    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject AuditService audit;
    @Inject SupportNotifier notifier;

    // ─── Côté structure ───

    /**
     * Ouvre un ticket au nom de la structure courante.
     *
     * <p>La priorité n'est pas demandée à la structure : tout le monde
     * coche « bloquant », et une file où tout est P1 ne se priorise plus.
     * L'éditeur requalifie ; la structure décrit.</p>
     */
    public SupportTicketDto open(UUID tenantId, UUID userId, CreateTicketDto payload) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantId));
        UserEntity author = users.findById(userId);

        SupportTicketEntity t = new SupportTicketEntity();
        t.id = UuidCreator.getTimeOrderedEpoch();
        t.ref = refs.next();
        t.tenantId = tenantId;
        t.tenantName = tenant.name;
        t.subject = payload.subject().trim();
        t.description = payload.description().trim();
        t.category = payload.category();
        t.priority = TicketPriority.P3;
        t.status = TicketStatus.OPEN;
        t.reportedBy = displayName(author);
        t.reportedByEmail = author == null ? null : author.email;
        t.reportedByUserId = userId;
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        tickets.persist(t);

        audit.event(AuditEventType.TICKET_OPENED)
                .actor(t.reportedByEmail, userId)
                .tenant(tenantId, tenant.name)
                .target("ticket", t.id.toString(), t.ref)
                .description(Messages.msg("m.tck-opened", t.ref, t.subject))
                .record();

        notifier.ticketOpened(t);
        return SupportTicketDto.forTenant(t);
    }

    public Pagination<SupportTicketDto> listForTenant(UUID tenantId, PageRequest pr) {
        List<SupportTicketDto> items = tickets.listForTenant(tenantId, pr.page(), pr.perPage())
                .stream().map(SupportTicketDto::forTenant).toList();
        return Pagination.of(tickets.countForTenant(tenantId), pr,
                new String[]{"createdAt"}, "desc", Map.of(), items);
    }

    public SupportTicketDto getForTenant(UUID tenantId, UUID ticketId) {
        return SupportTicketDto.forTenant(ownedBy(tenantId, ticketId));
    }

    /** Réponse de la structure dans le fil. */
    public SupportTicketDto replyAsTenant(UUID tenantId, UUID ticketId, UUID userId, TicketReplyDto payload) {
        SupportTicketEntity t = ownedBy(tenantId, ticketId);
        UserEntity author = users.findById(userId);
        // La notion de note interne n'existe pas côté structure : accepter
        // le drapeau donnerait un message que l'éditeur ne verrait jamais,
        // et la structure croirait avoir répondu.
        TicketMessageEntity m = append(t, payload.body(), displayName(author),
                author == null ? null : author.email, TicketAuthorRole.TENANT, false);

        // La balle revient à l'éditeur : un ticket en attente de la
        // structure repart en cours dès qu'elle a répondu.
        if (t.status == TicketStatus.WAITING) t.status = TicketStatus.IN_PROGRESS;
        tickets.update(t);

        audit.event(AuditEventType.TICKET_REPLIED)
                .actor(m.authorEmail, userId)
                .tenant(t.tenantId, t.tenantName)
                .target("ticket", t.id.toString(), t.ref)
                .description(Messages.msg("m.tck-replied", t.ref))
                .record();

        notifier.tenantReplied(t, m);
        return SupportTicketDto.forTenant(t);
    }

    // ─── Côté éditeur ───

    public Pagination<SupportTicketDto> search(TicketStatus status, TicketPriority priority,
                                               UUID tenantId, String assignedTo, PageRequest pr) {
        List<SupportTicketDto> items =
                tickets.search(status, priority, tenantId, assignedTo, pr.page(), pr.perPage())
                        .stream().map(SupportTicketDto::forStaff).toList();
        Map<String, String> filters = new LinkedHashMap<>();
        if (status != null) filters.put("status", status.name());
        if (priority != null) filters.put("priority", priority.name());
        if (tenantId != null) filters.put("tenantId", tenantId.toString());
        if (assignedTo != null && !assignedTo.isBlank()) filters.put("assignedTo", assignedTo.trim());
        return Pagination.of(tickets.countSearch(status, priority, tenantId, assignedTo), pr,
                new String[]{"createdAt"}, "desc", filters, items);
    }

    public SupportTicketDto getForStaff(UUID ticketId) {
        return SupportTicketDto.forStaff(existing(ticketId));
    }

    public SupportTicketDto assign(UUID ticketId, AssignTicketDto payload, String actorEmail, UUID actorId) {
        SupportTicketEntity t = existing(ticketId);
        String assignee = payload.assignee() == null || payload.assignee().isBlank()
                ? null : payload.assignee().trim();
        String previous = t.assignedTo;
        t.assignedTo = assignee;
        t.updatedAt = Instant.now();
        tickets.update(t);

        audit.event(AuditEventType.TICKET_ASSIGNED)
                .actor(actorEmail, actorId)
                .tenant(t.tenantId, t.tenantName)
                .target("ticket", t.id.toString(), t.ref)
                .description(assignee == null
                        ? Messages.msg("m.tck-unassigned", t.ref)
                        : Messages.msg("m.tck-assigned", t.ref, assignee))
                .payload(Map.of("previous", previous == null ? "" : previous))
                .record();
        return SupportTicketDto.forStaff(t);
    }

    public SupportTicketDto changePriority(UUID ticketId, TicketPriority priority,
                                           String actorEmail, UUID actorId) {
        SupportTicketEntity t = existing(ticketId);
        if (t.priority == priority) return SupportTicketDto.forStaff(t);
        TicketPriority previous = t.priority;
        t.priority = priority;
        t.updatedAt = Instant.now();
        tickets.update(t);

        audit.event(AuditEventType.TICKET_PRIORITY_CHANGED)
                .actor(actorEmail, actorId)
                .tenant(t.tenantId, t.tenantName)
                .target("ticket", t.id.toString(), t.ref)
                .description(Messages.msg("m.tck-priority-changed", t.ref, previous, priority))
                .record();
        return SupportTicketDto.forStaff(t);
    }

    /**
     * Fait transiter le ticket.
     *
     * <p>Le graphe est tenu ici et pas seulement dans le sélecteur du
     * back-office : une option grisée dans un écran n'empêche personne
     * d'appeler l'API, et un ticket clos qui redeviendrait « ouvert »
     * fausserait tout décompte de délai de réponse.</p>
     */
    public SupportTicketDto changeStatus(UUID ticketId, TicketStatus status,
                                         String actorEmail, UUID actorId) {
        SupportTicketEntity t = existing(ticketId);
        if (t.status == status) return SupportTicketDto.forStaff(t);
        if (!t.status.canMoveTo(status)) {
            throw new BusinessException(
                    Messages.msg("m.tck-transition-refused", t.status, status));
        }
        TicketStatus previous = t.status;
        t.status = status;
        t.updatedAt = Instant.now();
        if (status == TicketStatus.CLOSED) t.closedAt = t.updatedAt;
        tickets.update(t);

        audit.event(AuditEventType.TICKET_STATUS_CHANGED)
                .actor(actorEmail, actorId)
                .tenant(t.tenantId, t.tenantName)
                .target("ticket", t.id.toString(), t.ref)
                .description(Messages.msg("m.tck-status-changed", t.ref, previous, status))
                .record();

        notifier.statusChanged(t, previous);
        return SupportTicketDto.forStaff(t);
    }

    /** Réponse ou note interne de l'éditeur. */
    public SupportTicketDto replyAsStaff(UUID ticketId, TicketReplyDto payload,
                                         String actorName, String actorEmail, UUID actorId) {
        SupportTicketEntity t = existing(ticketId);
        TicketMessageEntity m = append(t, payload.body(), actorName, actorEmail,
                TicketAuthorRole.STAFF, payload.internal());

        // Une note interne ne fait pas avancer le ticket : elle ne dit
        // rien à la structure, qui attend toujours.
        if (!m.internal && t.status == TicketStatus.OPEN) t.status = TicketStatus.IN_PROGRESS;
        tickets.update(t);

        audit.event(m.internal ? AuditEventType.TICKET_NOTE_ADDED : AuditEventType.TICKET_REPLIED)
                .actor(actorEmail, actorId)
                .tenant(t.tenantId, t.tenantName)
                .target("ticket", t.id.toString(), t.ref)
                .description(Messages.msg(m.internal ? "m.tck-note-added" : "m.tck-replied", t.ref))
                .record();

        if (!m.internal) notifier.staffReplied(t, m);
        return SupportTicketDto.forStaff(t);
    }

    // ─── Helpers ───

    private SupportTicketEntity existing(UUID ticketId) {
        SupportTicketEntity t = tickets.findById(ticketId);
        if (t == null) throw new NotFoundException(Messages.msg("m.tck-not-found", ticketId));
        return t;
    }

    /**
     * Le ticket, à condition qu'il appartienne bien à la structure qui le
     * demande. Un identifiant deviné ne doit pas ouvrir le fil d'une
     * autre coopérative — les tickets vivent dans une collection commune,
     * la garde ne peut donc pas venir du cloisonnement des bases.
     */
    private SupportTicketEntity ownedBy(UUID tenantId, UUID ticketId) {
        SupportTicketEntity t = existing(ticketId);
        if (!t.tenantId.equals(tenantId)) {
            throw new ForbiddenException(Messages.msg("m.tck-not-yours"));
        }
        return t;
    }

    private TicketMessageEntity append(SupportTicketEntity t, String body, String authorName,
                                       String authorEmail, TicketAuthorRole role, boolean internal) {
        TicketMessageEntity m = new TicketMessageEntity();
        m.id = UuidCreator.getTimeOrderedEpoch();
        m.body = body.trim();
        m.authorName = authorName;
        m.authorEmail = authorEmail;
        m.authorRole = role;
        m.internal = internal;
        m.createdAt = Instant.now();
        t.messages.add(m);
        t.updatedAt = m.createdAt;
        return m;
    }

    private static String displayName(UserEntity u) {
        if (u == null) return null;
        String full = ((u.firstName == null ? "" : u.firstName) + " "
                + (u.lastName == null ? "" : u.lastName)).trim();
        return full.isEmpty() ? u.email : full;
    }
}
