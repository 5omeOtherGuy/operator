// FOUNDATION §2.3: results of one ILlmService call come back through a callback binder, one call per
// completion. Parcels stay under 256 KB against the 1 MB per-process binder buffer (ADR-0002).
package dev.operator.llm;

import dev.operator.llm.LlmStats;

/** One in-flight request's results. The client correlates by the GenerateRequest id it passed. */
oneway interface ILlmCallback {
    /** generate: one decoded token, in order. */
    void onToken(String text);

    /** tokenize: the token ids for the text. */
    void onTokenIds(in int[] ids);

    /** labelLogits: one row of logits, called once per label in the order the call passed them. */
    void onLogits(in float[] logits);

    /** embedLast: the last-row embedding (§2.3, M2). */
    void onEmbedding(in float[] values);

    /** generate / bench: the run's statistics. */
    void onStats(in LlmStats stats);

    /** The request finished. */
    void onDone();

    /** The request failed; @param reason is a stable short string. */
    void onError(String reason);
}
