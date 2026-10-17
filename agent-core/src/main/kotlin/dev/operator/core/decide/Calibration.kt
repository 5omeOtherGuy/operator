package dev.operator.core.decide

import dev.operator.core.api.BackendId
import dev.operator.core.api.DecisionKind

/*
 * S4 decide(): per-kind temperature scaling and abstention thresholds.
 *
 * Design: FOUNDATION §5.1 (router step 3 "Calibrator.apply(kind, logits)" and step 4 "the per-kind
 * threshold τ_kind"), §5.5 ("per-kind temperature scaling"; "τ_kind is the lowest threshold whose
 * held-out error meets the kind's risk budget; below it the answer is Abstained"; "Calibration files
 * are versioned by (backend, encoderId, headId, recipeVersion); a missing or mismatched file falls
 * back to conservative, abstain-heavy defaults"), §4.5 (placeholder deadlines), research/03-decide.md
 * §F7.1 (temperature scaling) and §F7.4, ADR-0007 decisions 7 and 9.
 */

/**
 * §5.5: one kind's calibration, a 1-D NLL fit [F7.1]. [temperature] scales the logits before the
 * softmax, [threshold] is τ_kind below which the router abstains. Isotonic is only for kinds with
 * ≥ 1000 labelled outcomes (S5/M3), so M1 carries a temperature only.
 */
data class KindCalibration(
    val temperature: Double,
    val threshold: Double,
) {
    init {
        require(temperature > 0.0) { "temperature must be positive, was $temperature" }
        require(threshold in 0.0..1.0) { "threshold must be in [0,1], was $threshold" }
    }
}

/**
 * §5.5, ADR-0007 decision 9: a calibration file is versioned by this tuple. A file whose tuple does
 * not equal the running (backend, encoderId, headId, recipeVersion) is "mismatched" and ignored in
 * favour of the conservative defaults.
 */
data class CalibrationKey(
    val backend: BackendId,
    /** sha256 of the encoder (the model file, §4.8); `none` for RULES. */
    val encoderId: String,
    /** sha256 of the heads file; null while there is no separate head (BONSAI_LOGPROB). */
    val headId: String?,
    /** The prompt/rendering recipe version (§F6.5). */
    val recipeVersion: String,
)

/**
 * §5.5: one calibration file: the tuple it was fitted for, a human-readable version, and one
 * [KindCalibration] per kind id. Missing kinds inside a present file fall back to the defaults for
 * those kinds (they are still conservative).
 */
data class CalibrationFile(
    val key: CalibrationKey,
    val version: String,
    val kinds: Map<String, KindCalibration>,
) {
    fun calibration(kind: DecisionKind): KindCalibration? = kinds[kind.id]

    /** The single-line-per-field text form [parse] reads; the file body S10 stores app-privately. */
    fun format(): String = buildString {
        appendLine("# operator decide calibration v1")
        appendLine("backend=${key.backend.name}")
        appendLine("encoderId=${key.encoderId}")
        appendLine("headId=${key.headId ?: ""}")
        appendLine("recipeVersion=${key.recipeVersion}")
        appendLine("version=$version")
        kinds.toSortedMap().forEach { (id, cal) ->
            appendLine("kind $id ${cal.temperature} ${cal.threshold}")
        }
    }

    companion object {
        /**
         * Parse the [format] text. `key=value` lines carry the tuple and version; `kind <id>
         * <temperature> <threshold>` lines carry the per-kind fit. `#` starts a comment. A malformed
         * line throws [IllegalArgumentException], so a corrupt file is reported, never half-read.
         */
        fun parse(text: String): CalibrationFile {
            var backend: BackendId? = null
            var encoderId: String? = null
            var headId: String? = null
            var recipeVersion: String? = null
            var version: String? = null
            val kinds = LinkedHashMap<String, KindCalibration>()

            text.lineSequence().forEachIndexed { lineNo, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                if (line.startsWith("kind ")) {
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size == 4) { "calibration line ${lineNo + 1}: expected 'kind <id> <t> <tau>'" }
                    kinds[parts[1]] = KindCalibration(
                        temperature = parts[2].toDouble(),
                        threshold = parts[3].toDouble(),
                    )
                    return@forEachIndexed
                }
                val eq = line.indexOf('=')
                require(eq > 0) { "calibration line ${lineNo + 1}: expected key=value or a kind line" }
                val name = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                when (name) {
                    "backend" -> backend = BackendId.valueOf(value)
                    "encoderId" -> encoderId = value
                    "headId" -> headId = value.ifEmpty { null }
                    "recipeVersion" -> recipeVersion = value
                    "version" -> version = value
                    // Unknown keys are ignored so a later format revision stays readable.
                }
            }

            return CalibrationFile(
                key = CalibrationKey(
                    backend = requireNotNull(backend) { "calibration file has no backend=" },
                    encoderId = requireNotNull(encoderId) { "calibration file has no encoderId=" },
                    headId = headId,
                    recipeVersion = requireNotNull(recipeVersion) { "calibration file has no recipeVersion=" },
                ),
                version = requireNotNull(version) { "calibration file has no version=" },
                kinds = kinds,
            )
        }
    }
}

/** §5.1 step 3: the outcome of calibration for one kind and one logit row. */
data class AppliedCalibration(
    /** `softmax(logits / temperature)`, in option order. */
    val probabilities: DoubleArray,
    val temperature: Double,
    /** τ_kind (§5.5); the policy may override it for one call. */
    val threshold: Double,
    /** The calibration file version, or null when conservative defaults were applied (§5.5). */
    val version: String?,
    /** §5.5: the file or the kind was missing/mismatched, so the abstain-heavy defaults applied. */
    val usedDefault: Boolean,
)

/** §5.1: the router's step 3. Temperature scaling and the per-kind threshold live behind this. */
interface Calibrator {
    fun apply(kind: DecisionKind, logits: FloatArray): AppliedCalibration
}

/**
 * §5.5: conservative, abstain-heavy defaults (τ high, neutral temperature 1) applied when the
 * calibration file is missing or mismatched, or when it does not name the kind.
 */
val CONSERVATIVE_DEFAULT: KindCalibration = KindCalibration(temperature = 1.0, threshold = 0.85)

/**
 * §5.5: temperature scaling for one calibration identity. Every `apply` recomputes the softmax, so
 * the router's step 4 can compare the calibrated top probability's confidence against τ_kind.
 */
class TemperatureCalibrator(
    private val file: CalibrationFile?,
    private val defaults: KindCalibration = CONSERVATIVE_DEFAULT,
) : Calibrator {
    override fun apply(kind: DecisionKind, logits: FloatArray): AppliedCalibration {
        val calibration = file?.calibration(kind)
        val usedDefault = calibration == null
        val effective = calibration ?: defaults
        return AppliedCalibration(
            probabilities = Scoring.softmax(logits, effective.temperature),
            temperature = effective.temperature,
            threshold = effective.threshold,
            version = if (usedDefault) null else file.version,
            usedDefault = usedDefault,
        )
    }
}

/**
 * §5.5: the calibration files on hand, looked up by the running tuple. A file that is missing or
 * mismatched produces a [TemperatureCalibrator] over the conservative defaults, so callers never
 * choose between "calibrated" and "uncalibrated": both return an [AppliedCalibration].
 */
class CalibrationStore(files: List<CalibrationFile> = emptyList()) {
    private val files: List<CalibrationFile> = files.toList()

    fun file(key: CalibrationKey): CalibrationFile? = this.files.firstOrNull { it.key == key }

    fun calibrator(key: CalibrationKey): Calibrator = TemperatureCalibrator(file(key))

    companion object {
        /** Parse every chunk of text into a file; a corrupt chunk fails the whole load. */
        fun parse(texts: List<String>): CalibrationStore =
            CalibrationStore(texts.map(CalibrationFile::parse))
    }
}
