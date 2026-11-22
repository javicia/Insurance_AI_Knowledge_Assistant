/**
 * Cost accounting for benchmark runs (FASE 27). {@link
 * com.rag.springai.insuranceai.application.benchmark.LlmCostCalculator} prices {@link
 * com.rag.springai.insuranceai.application.benchmark.TokenUsage} at the configurable unit rates
 * in {@link com.rag.springai.insuranceai.application.benchmark.LlmPricing}, always labelling the
 * result as observed or estimated ({@link
 * com.rag.springai.insuranceai.application.benchmark.LlmCost.Basis}).
 *
 * <p>Deliberately off the request path: nothing in {@code application.rag} depends on this
 * package. The RAG flow's only contribution is capturing the provider's reported token counts
 * in {@code LlmCompletion} (and mirroring them into the {@code rag.llm.tokens.*} meters) -
 * whether and how those get priced is a reporting concern, not part of answering a question.
 */
package com.rag.springai.insuranceai.application.benchmark;
