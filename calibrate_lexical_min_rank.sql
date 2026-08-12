-- FASE 26: evidence gathering for insurance-ai.rag.lexical.min-rank.
--
-- Replicates PostgresLexicalSearchAdapter's exact ranking expression
-- (ts_rank_cd(content_tsv, websearch_to_tsquery('english', ?))) against the REAL ingested corpus,
-- for two disjoint question sets:
--   (a) IN-CORPUS questions, which must stay above the threshold (else real answers stop being
--       grounded - a false negative), and
--   (b) OUT-OF-CORPUS questions, which must stay below it (else the system claims grounding it
--       does not have - a false positive, the dangerous direction for this product).
--
-- Run: docker exec -i insurance-ai-postgres psql -U insurance_ai -d insurance_ai -f -

\pset footer off

WITH questions(kind, question) AS (
    VALUES
        -- (a) In-corpus: each targets a document actually ingested in this database.
        ('in-corpus', 'Is water damage from a burst pipe covered?'),
        ('in-corpus', 'Is flood damage from a natural disaster excluded from coverage?'),
        ('in-corpus', 'Must claims be submitted within 30 days of the incident?'),
        ('in-corpus', 'Does travel medical coverage begin only after a waiting period of 14 days?'),
        ('in-corpus', 'What is the policy limit for water damage?'),
        ('in-corpus', 'How do I submit a claim form?'),
        ('in-corpus', 'What does the home insurance policy cover?'),
        ('in-corpus', 'What is the deductible for collision damage?'),
        -- (b) Out-of-corpus: plausible business questions with no supporting document at all.
        ('out-of-corpus', 'What is the CEO salary?'),
        ('out-of-corpus', 'What is the company current stock price?'),
        ('out-of-corpus', 'What is the office canteen menu today?'),
        ('out-of-corpus', 'Who won the football match last night?'),
        ('out-of-corpus', 'What is the wifi password for the meeting room?'),
        ('out-of-corpus', 'How many employees work in the marketing department?')
)
SELECT
    q.kind,
    q.question,
    COUNT(c.id)                                        AS matching_chunks,
    COALESCE(ROUND(MAX(c.rank)::numeric, 6), 0)        AS max_rank,
    COALESCE(ROUND(MIN(c.rank)::numeric, 6), 0)        AS min_rank
FROM questions q
LEFT JOIN LATERAL (
    SELECT dc.id, ts_rank_cd(dc.content_tsv, websearch_to_tsquery('english', q.question)) AS rank
    FROM public.vector_store dc
    WHERE dc.content_tsv @@ websearch_to_tsquery('english', q.question)
) c ON TRUE
GROUP BY q.kind, q.question
ORDER BY q.kind DESC, max_rank DESC;
