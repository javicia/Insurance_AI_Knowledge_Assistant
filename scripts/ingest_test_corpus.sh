#!/usr/bin/env bash
#
# Ingests the Spanish insurance test corpus into a RUNNING Insurance Knowledge Assistant stack,
# through the WAF - the same path a real browser takes. It does not start the stack.
#
#   ./scripts/ingest_test_corpus.sh
#
# Every upload goes to POST /api/documents (multipart) with a real Keycloak token, and the script
# then POLLS GET /api/documents/{id} until the version reaches EMBEDDED, because ingestion is
# asynchronous (Kafka -> chunking -> embedding). A document that never reaches EMBEDDED is
# reported as a failure rather than silently counted as ingested.
#
# Requirements: docker compose stack up and healthy, curl, python3, and the PDFs built by
# scripts/build_test_corpus_pdfs.py.

set -uo pipefail

# All overrides are prefixed - `USERNAME` and `PASSWORD` are NOT used, because Windows/Git-Bash
# already exports USERNAME (the OS account), which silently hijacked the login and produced a
# confusing "could not obtain an access token" against a perfectly healthy Keycloak.
WAF_BASE="${INSURANCE_AI_WAF_BASE:-http://localhost:8000}"
KEYCLOAK_BASE="${INSURANCE_AI_KEYCLOAK_BASE:-http://localhost:8180}"
REALM="${INSURANCE_AI_REALM:-insurance-ai}"
CLIENT_ID="${INSURANCE_AI_CLIENT_ID:-insurance-ai-e2e-test}"
CLIENT_SECRET="${INSURANCE_AI_CLIENT_SECRET:-e2e-test-dev-secret-never-used-in-production}"
LOGIN_USER="${INSURANCE_AI_USER:-alice.user}"
LOGIN_PASSWORD="${INSURANCE_AI_PASSWORD:-alice-password}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Everything below addresses files RELATIVE to the repository root on purpose. On Windows/Git-Bash
# an absolute path here is a POSIX-style "/d/Javier/..." which the native curl.exe cannot resolve -
# it fails the transfer outright and reports HTTP 000 with no error body, which looks exactly like
# the server being down. Relative paths work identically on Linux, macOS and Git-Bash.
cd "${REPO_ROOT}" || exit 1
CORPUS_DIR="test-data/insurance/auto"

EMBED_TIMEOUT_SECONDS="${EMBED_TIMEOUT_SECONDS:-120}"

uploaded=0
failed=0

json_field() {
    python3 -c "import json,sys; d=json.load(sys.stdin); print(d${1})" 2>/dev/null
}

# Keycloak access tokens in this realm live ~5 minutes. Ingesting the whole corpus takes longer
# than that, because the gateway rate-limits /api/documents to 10/min and the script waits out
# each 429 - so a single token fetched up front WILL expire mid-run and every remaining upload
# then fails with 401. The token is therefore refreshed proactively and whenever a 401 is seen.
fetch_token() {
    TOKEN=$(curl -s -X POST "${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token" \
        -d grant_type=password \
        -d "client_id=${CLIENT_ID}" \
        -d "client_secret=${CLIENT_SECRET}" \
        -d "username=${LOGIN_USER}" \
        -d "password=${LOGIN_PASSWORD}" | json_field "['access_token']")
    [ -n "${TOKEN}" ]
}

echo "==> Requesting an access token from Keycloak (${LOGIN_USER})"
if ! fetch_token; then
    echo "ERROR: could not obtain an access token. Is Keycloak up and healthy?" >&2
    echo "       Try: docker compose ps keycloak" >&2
    exit 1
fi
echo "    token acquired (${#TOKEN} chars)"

mapfile -t PDFS < <(find "${CORPUS_DIR}" -name '*.pdf' | sort)
if [ "${#PDFS[@]}" -eq 0 ]; then
    echo "ERROR: no PDFs under ${CORPUS_DIR}." >&2
    echo "       Build them first: python scripts/build_test_corpus_pdfs.py" >&2
    exit 1
fi

echo "==> Uploading ${#PDFS[@]} document(s) through the WAF at ${WAF_BASE}"

for pdf in "${PDFS[@]}"; do
    filename="$(basename "${pdf}")"
    # Human-readable document name derived from the filename: "cobertura-robo.pdf" -> "Cobertura Robo"
    name="$(basename "${pdf}" .pdf | tr '-' ' ' | python3 -c "import sys; print(sys.stdin.read().strip().title())")"

    # The corpus is internal insurer documentation; the claims-handling manuals are procedures.
    case "${pdf}" in
        *siniestros*|*reclamaciones*) doc_type="CLAIMS_PROCEDURE" ;;
        *politica-documentacion*|*glosario*|*preguntas-frecuentes*) doc_type="CORPORATE" ;;
        *) doc_type="POLICY" ;;
    esac

    # The gateway rate-limits /api/documents to 10 requests per minute per identity, and this
    # corpus is larger than that - so a 429 is EXPECTED here and is not a failure. Honour the
    # Retry-After the gateway advertises rather than hammering it or sleeping blindly.
    attempt=0
    status=""
    body=""
    while [ "${attempt}" -lt 6 ]; do
        response=$(curl -s -w '\n%{http_code}' -X POST "${WAF_BASE}/api/documents" \
            -H "Authorization: Bearer ${TOKEN}" \
            -F "file=@${pdf};type=application/pdf" \
            -F "name=${name}" \
            -F "type=${doc_type}" \
            -F "product=auto" \
            -F "country=ES" \
            -F "language=es" \
            -F "classification=INTERNAL")

        status="$(echo "${response}" | tail -n1)"
        body="$(echo "${response}" | sed '$d')"

        if [ "${status}" = "401" ]; then
            echo "  AUTH  ${filename}  token expired, refreshing"
            fetch_token || true
            attempt=$((attempt + 1))
            continue
        fi

        if [ "${status}" != "429" ]; then
            break
        fi

        retry_after=$(curl -s -o /dev/null -D - -X POST "${WAF_BASE}/api/documents" \
            -H "Authorization: Bearer ${TOKEN}" 2>/dev/null | tr -d '\r' \
            | awk 'tolower($1) == "retry-after:" { print $2 }')
        wait_for="${retry_after:-15}"
        echo "  WAIT  ${filename}  rate limited, retrying in ${wait_for}s"
        sleep "${wait_for}"
        attempt=$((attempt + 1))
    done

    if [ "${status}" != "201" ]; then
        echo "  FAIL  ${filename}  HTTP ${status}  ${body}"
        failed=$((failed + 1))
        continue
    fi

    doc_id="$(echo "${body}" | json_field "['id']")"

    # Poll until embedded - ingestion is asynchronous, so a 201 alone proves nothing.
    deadline=$((SECONDS + EMBED_TIMEOUT_SECONDS))
    doc_status="UNKNOWN"
    while [ "${SECONDS}" -lt "${deadline}" ]; do
        doc_status=$(curl -s "${WAF_BASE}/api/documents/${doc_id}" \
            -H "Authorization: Bearer ${TOKEN}" | json_field "['versions'][0]['status']")
        case "${doc_status}" in
            EMBEDDED|FAILED) break ;;
            "") fetch_token || true ;;  # empty means the read itself failed, usually an expired token
        esac
        sleep 2
    done

    if [ "${doc_status}" = "EMBEDDED" ]; then
        echo "  OK    ${filename}  ->  ${doc_id}  (${doc_type})"
        uploaded=$((uploaded + 1))
    else
        echo "  FAIL  ${filename}  ->  ${doc_id}  stuck in ${doc_status}"
        failed=$((failed + 1))
    fi
done

echo
echo "==> ${uploaded} document(s) ingested and EMBEDDED, ${failed} failed"
[ "${failed}" -eq 0 ] || exit 1
