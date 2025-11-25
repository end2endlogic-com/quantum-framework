/*
 * ACL Client Library
 *
 * This library provides client-side helpers for evaluating access decisions
 * against the scoped access matrix returned by the check-with-index endpoint
 * (proposed server shape). It is placed under META-INF/resources so Quarkus
 * can serve it statically at runtime:
 *   GET /security/acl-client.js
 *
 * NOTE: If your server currently returns only a flat RuleIndexSnapshot (rules[]),
 * these helpers will become fully useful once the server starts returning the
 * scoped matrix shape described in the design notes. Until then, the presence
 * of this library and its public API is stable for clients to adopt.
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

  function scopeKeyFromDataDomain(dataDomain) {
    const dd = dataDomain || {};
    const v = (x) => (x === undefined || x === null || String(x).trim() === '') ? '*' : String(x);
    return `org=${v(dd.orgRefName)}|acct=${v(dd.accountNumber)}|tenant=${v(dd.tenantId)}|seg=${v(dd.dataSegment)}|owner=${v(dd.ownerId)}`;
  }

  function buildFallbackChain(scopeKey) {
    // Expand by progressively widening owner -> segment -> tenant -> account -> org
    const parts = parseScopeKey(scopeKey);
    const chain = [];
    if (!parts) return chain;

    const steps = [
      ['owner', '*'],
      ['seg', '*'],
      ['tenant', '*'],
      ['acct', '*'],
      ['org', '*']
    ];

    let current = { ...parts };
    for (const [k, val] of steps) {
      current = { ...current, [k]: val };
      chain.push(formatScopeKey(current));
    }
    // Always end with global just in case (may duplicate last)
    const globalKey = 'org=*|acct=*|tenant=*|seg=*|owner=*';
    if (chain[chain.length - 1] !== globalKey) chain.push(globalKey);
    return chain;
  }

  function parseScopeKey(key) {
    if (!key || typeof key !== 'string') return null;
    const obj = {};
    const parts = key.split('|');
    for (const p of parts) {
      const [k, v] = p.split('=');
      if (!k) return null;
      obj[k] = (v === undefined ? '*' : v);
    }
    return obj.org && obj.acct && obj.tenant && obj.seg && obj.owner ? obj : null;
  }

  function formatScopeKey(parts) {
    return `org=${parts.org}|acct=${parts.acct}|tenant=${parts.tenant}|seg=${parts.seg}|owner=${parts.owner}`;
  }

  function lookupAreaDomainAction(m, area, domain, action) {
    const tries = [
      [area, domain, action],
      [area, domain, '*'],
      [area, '*', action],
      [area, '*', '*'],
      ['*', domain, action],
      ['*', domain, '*'],
      ['*', '*', action],
      ['*', '*', '*']
    ];
    for (const [a, d, c] of tries) {
      const D = m[a]; if (!D) continue;
      const A = D[d]; if (!A) continue;
      const out = A[c]; if (out) return out;
    }
    return null;
  }

  function decide(snapshot, dataDomain, area, domain, action) {
    if (!snapshot || !snapshot.enabled) return 'DENY';

    const startKey = dataDomain
      ? scopeKeyFromDataDomain(dataDomain)
      : (snapshot.requestedScope || 'org=*|acct=*|tenant=*|seg=*|owner=*');

    const chain = dataDomain
      ? buildFallbackChain(startKey)
      : (snapshot.requestedFallback || ['org=*|acct=*|tenant=*|seg=*|owner=*']);

    const tryScopes = [startKey, ...chain];
    for (const key of tryScopes) {
      const scoped = snapshot.scopes ? snapshot.scopes[key] : null;
      if (!scoped || scoped.requiresServer) continue;
      const m = scoped.matrix;
      const out = lookupAreaDomainAction(m, area, domain, action);
      if (out) return String(out.effect).toUpperCase();
    }
    return 'DENY';
  }

  function decideOutcome(snapshot, dataDomain, area, domain, action) {
    if (!snapshot || !snapshot.enabled) return null;

    const startKey = dataDomain
      ? scopeKeyFromDataDomain(dataDomain)
      : (snapshot.requestedScope || 'org=*|acct=*|tenant=*|seg=*|owner=*');

    const chain = dataDomain
      ? buildFallbackChain(startKey)
      : (snapshot.requestedFallback || ['org=*|acct=*|tenant=*|seg=*|owner=*']);

    const tryScopes = [startKey, ...chain];
    for (const key of tryScopes) {
      const scoped = snapshot.scopes ? snapshot.scopes[key] : null;
      if (!scoped || scoped.requiresServer) continue;
      const m = scoped.matrix;
      const out = lookupAreaDomainAction(m, area, domain, action);
      if (out) return out; // { effect, rule, priority, finalRule, source }
    }
    return null;
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
