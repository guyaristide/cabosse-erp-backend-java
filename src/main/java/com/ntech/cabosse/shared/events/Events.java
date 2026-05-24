package com.ntech.cabosse.shared.events;

/**
 * Adresses (topics) de l'event bus Vert.x utilisé par Quarkus.
 *
 * Convention de nommage : {@code <domaine>.<événement>} en
 * lowercase pointé, snake_case interne si besoin.
 *
 * Toute string littérale dans {@code .publish()} ou {@code @ConsumeEvent}
 * en dehors de cette classe est interdite (cf. shared-constants.md §5).
 */
public final class Events {

    private Events() {}

    // === Cycle de vie tenant ===
    public static final String TENANT_PROVISIONED = "tenant.provisioned";
    public static final String TENANT_SUSPENDED   = "tenant.suspended";
    public static final String TENANT_REACTIVATED = "tenant.reactivated";
    public static final String TENANT_DELETED     = "tenant.deleted";
    public static final String TENANT_BACKUP_REQUESTED = "tenant.backup_requested";

    // === Cycle de vie utilisateur ===
    public static final String USER_INVITED       = "user.invited";
    public static final String USER_ACTIVATED     = "user.activated";
    public static final String USER_PASSWORD_RESET_REQUESTED = "user.password.reset_requested";

    // === Achats ===
    public static final String PURCHASE_ORDER_CONFIRMED = "purchase.order.confirmed";
    public static final String PURCHASE_ORDER_RECEIVED  = "purchase.order.received";

    // === Production ===
    public static final String PRODUCTION_ORDER_STARTED   = "production.order.started";
    public static final String PRODUCTION_ORDER_COMPLETED = "production.order.completed";

    // === Stocks ===
    public static final String STOCK_LOW_THRESHOLD = "stock.low_threshold";

    // === Ventes ===
    public static final String SALES_ORDER_PLACED  = "sales.order.placed";
    public static final String SALES_ORDER_SHIPPED = "sales.order.shipped";
}
