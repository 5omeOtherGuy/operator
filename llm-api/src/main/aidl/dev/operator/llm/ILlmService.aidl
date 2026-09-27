// FOUNDATION §2.3: the `:llm` AIDL surface, one method per row of the table. Calls are oneway where
// possible; every result (tokens, logits, stats, errors) comes back through ILlmCallback.
package dev.operator.llm;

import dev.operator.llm.GenerateRequest;
import dev.operator.llm.ILlmCallback;
import dev.operator.llm.LabelTokens;
import dev.operator.llm.LlmStats;
import dev.operator.llm.ModelSpec;

interface ILlmService {
    /** load: type allowlist and sha256 check, then llama_model_load_from_file + llama_init_from_model. */
    oneway void load(in ModelSpec spec, in ILlmCallback cb);

    /** unload(role). */
    oneway void unload(String role, in ILlmCallback cb);

    /** tokenize(role, text, addSpecial, parseSpecial): token parity (G0) and prefix computation. */
    oneway void tokenize(String role, String text, boolean addSpecial, boolean parseSpecial, in ILlmCallback cb);

    /** generate(req, role, promptParts, gbnf, maxTokens, cb): grammar sampling with prefix reuse. */
    oneway void generate(in GenerateRequest req, String role, in List<String> promptParts, String gbnf, int maxTokens, in ILlmCallback cb);

    /** labelLogits(req, role, promptParts, labelTokenIds): BONSAI_LOGPROB on a branch sequence. */
    oneway void labelLogits(in GenerateRequest req, String role, in List<String> promptParts, in List<LabelTokens> labels, in ILlmCallback cb);

    /** embedLast(req, role, tokens): CLM-8B encoder, pooling NONE, last row (M2). */
    oneway void embedLast(in GenerateRequest req, String role, in int[] tokens, in ILlmCallback cb);

    /** stateSave(role, path) / stateRestore(role, path): static-prefix cache. */
    oneway void stateSave(String role, String path, in ILlmCallback cb);

    oneway void stateRestore(String role, String path, in ILlmCallback cb);

    /** stateCheckpoint(role, tag, partialOnly): 27B PARTIAL_ONLY checkpoints. */
    oneway void stateCheckpoint(String role, String tag, boolean partialOnly, in ILlmCallback cb);

    /** abort(req): cooperative stop (per-token flag plus the CPU abort callback). */
    oneway void abort(long reqId, in ILlmCallback cb);

    /** pid(): lets the main process kill :llm by PID for the hard stop. */
    int pid();

    /** stats(). */
    LlmStats stats();

    /** bench(pp, tg, threads). */
    LlmStats bench(int pp, int tg, int threads);
}
