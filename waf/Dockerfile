# syntax=docker/dockerfile:1
#
# FASE 20/21: a real WAF (nginx + ModSecurity v3 + OWASP Core Rule Set), the single browser-facing
# edge (brief: "Internet -> WAF -> Gateway/Frontend"). Reconciles the two slightly different
# topology diagrams given across this program's phases (one shows WAF splitting directly to
# frontend+gateway in parallel, another shows WAF -> Gateway -> {frontend, backend}) by making the
# WAF the one browser-facing origin that internally routes by path: /api/** to the gateway,
# everything else to the frontend - documented in docs/adr/ADR-016-WAF-EDGE.md.
#
# This is a real, evaluatable WAF (OWASP CRS's actual anomaly-scoring rule engine, not a hand-
# rolled regex filter) - not a claim of enterprise/commercial WAF equivalence. See
# docs/security/SECURITY_HARDENING.md for exactly what it covers and what it deliberately does not
# (SQL injection and XSS protection remain the application's own responsibility - parameterized
# queries and Angular's built-in sanitization - never delegated to this layer as the only defense).
# FASE 34 (container hardening): pinned to the exact CRS/nginx build in use, not the rolling
# `nginx` tag - reproducible builds, no surprise rule-set upgrade on a fresh `docker compose pull`.
FROM owasp/modsecurity-crs:3.3.10-nginx-202608050608

COPY conf/default.conf.template /etc/nginx/templates/conf.d/default.conf.template
