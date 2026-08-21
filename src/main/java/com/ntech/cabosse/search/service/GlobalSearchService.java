package com.ntech.cabosse.search.service;

import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.production.repository.ManufacturingOrderRepository;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.search.dto.SearchHitDto;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recherche globale transverse (barre du haut / palette Cmd+K). Interroge les
 * référentiels et documents opérationnels clés du tenant courant et renvoie une
 * liste unifiée de résultats typés. Chaque entité est limitée à {@code perType}
 * résultats pour garder la réponse compacte et rapide.
 */
@ApplicationScoped
public class GlobalSearchService {

    /** Longueur minimale de requête (évite de balayer sur 1 caractère). */
    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_PER_TYPE = 10;

    @Inject PermissionResolver permissions;
    @Inject CustomerRepository customers;
    @Inject SupplierRepository suppliers;
    @Inject ArticleRepository articles;
    @Inject MemberRepository members;
    @Inject PurchaseOrderRepository purchaseOrders;
    @Inject SaleRepository sales;
    @Inject ManufacturingOrderRepository ofs;

    public List<SearchHitDto> search(String q, int perType) {
        if (q == null || q.trim().length() < MIN_QUERY_LENGTH) return List.of();
        String s = q.trim();
        int n = perType <= 0 ? 5 : Math.min(perType, MAX_PER_TYPE);
        List<SearchHitDto> hits = new ArrayList<>();

        // Chaque type de résultat est soumis au droit de lecture de son
        // module (backlog SAAS-03). Sans ce filtre, la palette montrait à
        // un magasinier les ventes et les producteurs qu'aucun de ses
        // écrans ne lui aurait montrés : une recherche n'est pas une porte
        // dérobée autour des profils.
        Set<Permission> granted = permissions.current();

        if (granted.contains(Permission.SALE_READ)) {
            customers.search(s, null, 0, n).forEach(c ->
                    hits.add(new SearchHitDto("customer", c.id.toString(), c.name, c.code)));
        }
        if (granted.contains(Permission.REFERENTIAL_READ)) {
            suppliers.search(s, 0, n).forEach(x ->
                    hits.add(new SearchHitDto("supplier", x.id.toString(), x.name, x.code)));
            articles.search(null, s, 0, n).forEach(a ->
                    hits.add(new SearchHitDto("article", a.id.toString(), a.name, a.code)));
        }
        if (granted.contains(Permission.MEMBER_READ)) {
            members.search(s, null, 0, n).forEach(m ->
                    hits.add(new SearchHitDto("member", m.id.toString(), m.name, m.code)));
        }
        if (granted.contains(Permission.PURCHASE_READ)) {
            purchaseOrders.search(null, s, 0, n).forEach(po ->
                    hits.add(new SearchHitDto("purchaseOrder", po.id.toString(), po.ref, po.supplierName)));
        }
        if (granted.contains(Permission.SALE_READ)) {
            sales.search(null, s, null, null, 0, n).forEach(sa ->
                    hits.add(new SearchHitDto("sale", sa.id.toString(), sa.ref, sa.customerName)));
        }

        // Lots : dérivés des ordres de fabrication portant un lotRef. On
        // dédoublonne et on plafonne comme les autres types.
        if (granted.contains(Permission.TRACEABILITY_READ)
                || granted.contains(Permission.PROCESSING_READ)) {
            Set<String> seenLots = new HashSet<>();
            for (var of : ofs.search(null, s, null, 0, n * 2)) {
                if (of.lotRef == null || of.lotRef.isBlank() || !seenLots.add(of.lotRef)) continue;
                hits.add(new SearchHitDto("lot", of.lotRef, of.lotRef, of.finishedProductName));
                if (seenLots.size() >= n) break;
            }
        }

        return hits;
    }
}
