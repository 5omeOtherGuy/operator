// operator patch set P2 (FOUNDATION §4.2, OQ-21 default (a)).
//
// S1 adds the handle-based JNI entry points here: model, context and sequence handles for grammar
// sampling, label logits on a branch sequence, embedLast, state save/restore,
// llama_attach_threadpool and the abort callback. The upstream globals in ai_chat.cpp stay for the
// upstream chat path, which only stage 0 and the smoke test use.
//
// F0 keeps this translation unit empty so that the target, the package and the .so set are real and
// the CMake delta against the submodule stays reviewable.
