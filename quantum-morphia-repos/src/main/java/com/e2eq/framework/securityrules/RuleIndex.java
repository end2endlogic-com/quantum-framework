package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.util.IOCase;
import com.e2eq.framework.util.WildCardMatcher;
import io.quarkus.logging.Log;

import java.util.*;

/**
 * Optional precompiled rule discrimination index. This is intentionally simple and
 * conservative for an initial opt-in release. It indexes rules by the stable
 * header dimensions (identity, area, functionalDomain, action) with wildcard support.
 *
 * When disabled, RuleContext falls back to legacy list scanning. When enabled, this
 * index is used to quickly gather a smaller, prioritized candidate list.
 */
final class RuleIndex {

    static final class Node {
        // exact value children for this level
        final Map<String, Node> exact = new HashMap<>();
        // wildcard branch for "*"
        Node wildcard;
        // rules terminated at this node
        List<Rule> rules; // stored sorted by priority asc
    }

    private final Node root = new Node();
    private final long version;

    private RuleIndex(long version) {
        this.version = version;
    }

    long getVersion() { return version; }

    static RuleIndex build(Collection<Rule> rules) {
        long v = System.nanoTime();
        RuleIndex idx = new RuleIndex(v);
        int added = 0;
        for (Rule r : rules) {
            try {
                if (r == null || r.getSecurityURI() == null || r.getSecurityURI().getHeader() == null) continue;
                SecurityURIHeader h = r.getSecurityURI().getHeader();
                String identity = n(h.getIdentity());
                String area = n(h.getArea());
                String domain = n(h.getFunctionalDomain());
                String action = n(h.getAction());
                // Insert along the path identity/area/domain/action
                Node node = idx.root;
                node = step(node, identity);
                node = step(node, area);
                node = step(node, domain);
                node = step(node, action);
                if (node.rules == null) node.rules = new ArrayList<>();
                node.rules.add(r);
                added++;
            } catch (Exception e) {
                Log.warnf(e, "RuleIndex: skipping rule due to error during indexing: %s", String.valueOf(r));
            }
        }
        // sort each node's rule list by priority asc to match existing behavior
        sortAll(idx.root);
        Log.debugf("RuleIndex built with %d rules, version=%d", added, v);
        return idx;
    }

    private static String n(String s) { return (s == null || s.isBlank()) ? "*" : s; }

    private static Node step(Node node, String key) {
        if ("*".equals(key)) {
            if (node.wildcard == null) node.wildcard = new Node();
            return node.wildcard;
        }
        return node.exact.computeIfAbsent(key, k -> new Node());
    }

    private static void sortAll(Node node) {
        if (node == null) return;
        if (node.rules != null && node.rules.size() > 1) {
            node.rules.sort(Comparator.comparingInt(Rule::getPriority));
        }
        if (node.wildcard != null) sortAll(node.wildcard);
        for (Node child : node.exact.values()) sortAll(child);
    }

    /**
     * Collects applicable rules for the given principal/resource contexts.
     * This will traverse exact and wildcard branches to honor both specific and generic rules.
     */
    List<Rule> getApplicableRules(PrincipalContext pc, ResourceContext rc) {
        // identities include the principalId and associated roles
        List<String> identities = new ArrayList<>();
        if (pc.getUserId() != null) identities.add(pc.getUserId());
        identities.addAll(Arrays.asList(pc.getRoles()));

        String area = rc.getArea() == null ? "*" : rc.getArea();
        String domain = rc.getFunctionalDomain() == null ? "*" : rc.getFunctionalDomain();
        String action = rc.getAction() == null ? "*" : rc.getAction();

        // Use a linked hash set to preserve insertion order and avoid duplicates
        LinkedHashSet<Rule> out = new LinkedHashSet<>();
        for (String id : identities) {
            collect(root, id, area, domain, action, pc, rc, out);
        }
        // As a final safety, ensure priority ordering overall
        List<Rule> list = new ArrayList<>(out);
        list.sort(Comparator.comparingInt(Rule::getPriority));
        return list;
    }

    private void collect(Node node, String identity, String area, String domain, String action, PrincipalContext pc, ResourceContext rc, LinkedHashSet<Rule> out) {
        if (node == null) return;
        // Traverse identity level (exact and wildcard)
        List<Node> idNodes = childrenFor(node, identity);
        for (Node idNode : idNodes) {
            // area level
            List<Node> areaNodes = childrenFor(idNode, area);
            for (Node aNode : areaNodes) {
                // domain level
                List<Node> domNodes = childrenFor(aNode, domain);
                for (Node dNode : domNodes) {
                    // action level
                    List<Node> actNodes = childrenFor(dNode, action);
                    for (Node actNode : actNodes) {
                        if (actNode.rules != null) {
                            // Additional safety: verify full URI wildcard match using full principal URI shape
                            for (Rule r : actNode.rules) {
                                try {
                                    String lhs = buildPrincipalUri(identity, area, domain, action, pc, rc);
                                    String rhs = r.getSecurityURI().uriString();
                                    if (WildCardMatcher.wildcardMatch(lhs, rhs, IOCase.INSENSITIVE)) {
                                        out.add(r);
                                    }
                                } catch (Exception e) {
                                    out.add(r); // be permissive on errors; ordering maintained
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static List<Node> childrenFor(Node node, String key) {
        List<Node> res = new ArrayList<>(2);
        if (node == null) return res;
        Node exact = node.exact.get(key);
        if (exact != null) res.add(exact);
        if (node.wildcard != null) res.add(node.wildcard);
        return res;
    }

    private static String buildPrincipalUri(String identity, String area, String domain, String action, PrincipalContext pc, ResourceContext rc) {
        // Build a URI string that matches SecurityURI.getURIString(): "header|body"
        String header = (identity == null ? "*" : identity.toLowerCase()) + ":"
                + (area == null ? "*" : area.toLowerCase()) + ":"
                + (domain == null ? "*" : domain.toLowerCase()) + ":"
                + (action == null ? "*" : action.toLowerCase());

        String realm = pc != null && pc.getDefaultRealm() != null ? pc.getDefaultRealm().toLowerCase() : "*";
        String orgRef = pc != null && pc.getDataDomain() != null && pc.getDataDomain().getOrgRefName() != null ? pc.getDataDomain().getOrgRefName().toLowerCase() : "*";
        String acct = pc != null && pc.getDataDomain() != null && pc.getDataDomain().getAccountNum() != null ? pc.getDataDomain().getAccountNum().toLowerCase() : "*";
        String tenant = pc != null && pc.getDataDomain() != null && pc.getDataDomain().getTenantId() != null ? pc.getDataDomain().getTenantId().toLowerCase() : "*";
        String dataSeg = pc != null && pc.getDataDomain() != null ? Integer.toString(pc.getDataDomain().getDataSegment()) : "*";
        String owner = identity == null ? "*" : identity.toLowerCase();
        String resourceId = rc != null && rc.getResourceId() != null ? rc.getResourceId().toLowerCase() : "*";

        String body = realm + ":" + orgRef + ":" + acct + ":" + tenant + ":" + dataSeg + ":" + owner + ":" + resourceId;
        return header + "|" + body;
    }
}
