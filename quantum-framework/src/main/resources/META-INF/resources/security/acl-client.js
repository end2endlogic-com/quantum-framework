/*
 * ACL Client Library
 *
 * This library provides client-side helpers for evaluating access decisions
 * against the snapshot returned by the check-with-index endpoint. It is placed
 * under META-INF/resources so Quarkus can serve it statically at runtime:
 *   GET /security/acl-client.js
 *
 * Decisions only choose what to show; the backend enforces every request.
 */

(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    // AMD
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    // CommonJS
    module.exports = factory();
  } else {
    // Browser global
    root.ACLClient = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // The server decides every registered action with the rule engine and returns the allowed ones,
  // lower case, in one scope: the data domain the snapshot was requested for (requestedScope).
  // A cell is allowed; anything else is denied.

  const GLOBAL = 'org=*|acct=*|tenant=*|seg=*|owner=*';

  function part(x) {
    return (x === undefined || x === null || String(x).trim() === '') ? '*' : String(x).trim().toLowerCase();
  }

  function scopeKeyFromDataDomain(dataDomain) {
    const dd = dataDomain || {};
    return `org=${part(dd.orgRefName)}|acct=${part(dd.accountNumber)}|tenant=${part(dd.tenantId)}|seg=${part(dd.dataSegment)}|owner=${part(dd.ownerId)}`;
  }

  /** Kept for compatibility. The server returns one scope, so there is nothing to widen to. */
  function buildFallbackChain(scopeKey) {
    return scopeKey ? [String(scopeKey).toLowerCase()] : [GLOBAL];
  }

  function own(record, key) {
    return record && Object.prototype.hasOwnProperty.call(record, key) ? record[key] : undefined;
  }

  function lookupAreaDomainAction(m, area, domain, action) {
    const out = own(own(own(m, String(area).toLowerCase()), String(domain).toLowerCase()), String(action).toLowerCase());
    return out || null;
  }

  /**
   * The snapshot's outcome for one action, or null. With a dataDomain, only the data domain the
   * snapshot was requested for is answered; for any other, ask /system/permissions/check.
   */
  function decideOutcome(snapshot, dataDomain, area, domain, action) {
    if (!snapshot || !snapshot.enabled || snapshot.requiresServer) return null;
    const key = String(snapshot.requestedScope || '').toLowerCase();
    if (!key) return null;
    if (dataDomain && scopeKeyFromDataDomain(dataDomain) !== key) return null;
    let scoped = own(snapshot.scopes, key);
    if (!scoped && snapshot.scopes) {
      for (const name of Object.keys(snapshot.scopes)) if (name.toLowerCase() === key) scoped = snapshot.scopes[name];
    }
    if (!scoped || scoped.requiresServer) return null;
    return lookupAreaDomainAction(scoped.matrix, area, domain, action); // { effect, rule, priority, finalRule }
  }

  function decide(snapshot, dataDomain, area, domain, action) {
    const out = decideOutcome(snapshot, dataDomain, area, domain, action);
    return out && String(out.effect).toUpperCase() === 'ALLOW' ? 'ALLOW' : 'DENY';
  }

  return {
    scopeKeyFromDataDomain,
    buildFallbackChain,
    lookupAreaDomainAction,
    decide,
    decideOutcome,
    // New helper: interpret SecurityCheckResponse from /system/permissions/check
    interpretCheckResponse: function (check) {
      if (!check) return { decision: 'DENY', scope: 'DEFAULT', constraints: [] };
      const decision = (check.decision || (check.finalEffect ? String(check.finalEffect).toUpperCase() : 'DENY'));
      const scope = (check.decisionScope || 'DEFAULT');
      const constraints = Array.isArray(check.scopedConstraints) ? check.scopedConstraints : [];
      return {
        decision,
        scope,
        constraints,
        // Back-compat: filterConstraints for older clients
        filterConstraintsPresent: !!check.filterConstraintsPresent,
        filterConstraints: Array.isArray(check.filterConstraints) ? check.filterConstraints : []
      };
    },
    // New helper: interpret /system/permissions/fd/evaluate response
    // Returns a lightweight accessor for per-action decisions with scope/constraints
    interpretEvaluateResponse: function (res) {
      if (!res) return { allow: {}, deny: {}, decisions: {}, evalModeUsed: 'LEGACY' };
      const allow = res.allow || {};
      const deny = res.deny || {};
      const decisions = res.decisions || {};
      const evalModeUsed = res.evalModeUsed || 'LEGACY';
      function getDecision(area, domain, action) {
        const dom = decisions && decisions[area];
        if (!dom) return null;
        const actMap = dom[domain];
        if (!actMap) return null;
        return actMap[action] || null; // { effect, decisionScope, scopedConstraintsPresent, scopedConstraints, naLabel, rule, priority, finalRule, source }
      }
      return { allow, deny, decisions, evalModeUsed, getDecision };
    }
  };
}));
