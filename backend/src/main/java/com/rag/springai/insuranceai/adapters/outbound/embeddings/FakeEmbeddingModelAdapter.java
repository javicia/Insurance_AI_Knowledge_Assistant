package com.rag.springai.insuranceai.adapters.outbound.embeddings;

import com.rag.springai.insuranceai.domain.rag.EmbeddingModelDescriptor;
import com.rag.springai.insuranceai.domain.rag.EmbeddingVector;
import com.rag.springai.insuranceai.domain.rag.SimilarityMetric;
import com.rag.springai.insuranceai.ports.outbound.EmbeddingModelPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

    /**
     * Unicode-aware since FASE 29. This was {@code [a-z0-9]+}, which matches no accented Spanish
     * character and therefore split every such word at the accent. Measured on the real corpus
     * vocabulary:
     *
     * <pre>
     *   "daños"          -&gt; ["da", "os"]
     *   "colisión"       -&gt; ["colisi", "n"]
     *   "póliza"         -&gt; ["p", "liza"]
     *   "vehículo"       -&gt; ["veh", "culo"]
     *   "indemnización"  -&gt; ["indemnizaci", "n"]
     * </pre>
     *
     * Two compounding harms, not one. The obvious one is lost signal. The worse one is
     * manufactured signal: <em>every</em> word ending in {@code -ión} emits the same {@code "n"}
     * fragment, so {@code colisión}, {@code exclusión}, {@code indemnización} and
     * {@code protección} all pile into one hash bucket. Unrelated documents are thereby pushed
     * together in vector space, which is a direct mechanism for a false semantic match - and the
     * semantic branch is the one branch term coverage cannot gate (see
     * {@code AskInsuranceKnowledgeUseCase#isSufficientEvidence}).
     *
     * <p><b>Changing this changes the embedding function</b>, so vectors produced by the previous
     * tokenizer are not comparable with queries produced by this one. That is why
     * {@link #descriptor()} reports {@code v2}: {@code vector_store.metadata} records
     * {@code embeddingModelVersion} per chunk, so a corpus still holding {@code v1} vectors is
     * detectable rather than silently mis-scored. A deployment taking this change must re-embed -
     * see {@code docs/rag/FASE_29_CALIBRACION_GROUNDING_ES.md}.
     */
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+",
            Pattern.UNICODE_CHARACTER_CLASS);

    private final MeterRegistry meterRegistry;

    public FakeEmbeddingModelAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Timed as {@code rag.embedding.latency} tagged {@code provider="fake"} exactly like the real
     * adapter (FASE 27, benchmarking) - and the {@code provider} tag is precisely what keeps the
     * two apart: an in-process hashing loop measured in microseconds must never be mistaken for,
     * or averaged together with, a real OpenAI embedding round trip.
     */
    @Override
    public EmbeddingVector embed(String text) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doEmbed(text);
        }
        finally {
            sample.stop(meterRegistry.timer("rag.embedding.latency", "provider", "fake"));
        }
    }

    private EmbeddingVector doEmbed(String text) {
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
        // v2 since FASE 29's Unicode tokenizer fix. The version is persisted per chunk in
        // vector_store.metadata.embeddingModelVersion, which is what makes a corpus embedded by
        // the old tokenizer identifiable instead of silently incomparable.
        return new EmbeddingModelDescriptor("fake", "hashing-bag-of-words", "v2", DIMENSIONS,
                SimilarityMetric.COSINE);
    }
}
