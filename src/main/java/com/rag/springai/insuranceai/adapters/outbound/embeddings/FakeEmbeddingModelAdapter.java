package com.rag.springai.insuranceai.adapters.outbound.embeddings;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.SimilarityMetric;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline, non-ML "embedding": a classic feature-hashing bag-of-words vector
 * (each word hashes into one of {@link #DIMENSIONS} buckets, the bucket counts are then
 * L2-normalized). It produces vectors close together for texts sharing vocabulary and far
 * apart otherwise - just enough to exercise real cosine-similarity retrieval end to end without
 * any network call or API key (brief section 17/18).
 *
 * <p><b>This is not a real embedding model</b> and must never be presented as one. It exists
 * solely so the FASE 5 pipeline (embed -&gt; index -&gt; retrieve -&gt; ground) can be validated -
 * automatically and manually - in environments without OpenAI/Anthropic credentials. Selected
 * only when {@code insurance-ai.ai.provider: fake}.
 *
 * <p>{@link #DIMENSIONS} is 1536, matching {@code V3__vector_store.sql}'s fixed-width
 * {@code embedding vector(1536)} column exactly: pgvector rejects any vector whose dimension
 * does not match the column's declared width, regardless of which model produced it.
 */
@Component
@ConditionalOnProperty(prefix = "insurance-ai.ai", name = "provider", havingValue = "fake")
public class FakeEmbeddingModelAdapter implements EmbeddingModelPort {

    static final int DIMENSIONS = 1536;

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z0-9]+");

    @Override
    public EmbeddingVector embed(String text) {
        double[] buckets = new double[DIMENSIONS];
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            int bucket = Math.floorMod(matcher.group().hashCode(), DIMENSIONS);
            buckets[bucket] += 1.0;
        }

        double norm = Math.sqrt(java.util.Arrays.stream(buckets).map(v -> v * v).sum());
        List<Float> values = new ArrayList<>(DIMENSIONS);
        for (double bucket : buckets) {
            values.add(norm > 0 ? (float) (bucket / norm) : 0f);
        }
        return new EmbeddingVector(values);
    }

    @Override
    public EmbeddingModelDescriptor descriptor() {
        return new EmbeddingModelDescriptor("fake", "hashing-bag-of-words", "v1", DIMENSIONS,
                SimilarityMetric.COSINE);
    }
}
