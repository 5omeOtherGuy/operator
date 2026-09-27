package dev.operator.core.decide

import dev.operator.core.api.BackendId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.5: temperature scaling, the versioned tuple, and the conservative fallback. */
class CalibrationTest {

    private val key = CalibrationKey(
        backend = BackendId.BONSAI_LOGPROB,
        encoderId = "sha256-model",
        headId = null,
        recipeVersion = "logprob-v0",
    )

    @Test
    fun `format and parse round-trip the versioned tuple and kinds`() {
        val file = CalibrationFile(
            key = key,
            version = "operator-logprob-0001",
            kinds = linkedMapOf(
                "task.done" to KindCalibration(temperature = 2.0, threshold = 0.6),
                "action.irreversible" to KindCalibration(temperature = 1.5, threshold = 0.8),
            ),
        )

        val parsed = CalibrationFile.parse(file.format())

        assertEquals(file, parsed)
        assertEquals(BackendId.BONSAI_LOGPROB, parsed.key.backend)
        assertNull(parsed.key.headId)
        assertEquals(KindCalibration(2.0, 0.6), parsed.calibration(Kinds.TASK_DONE))
    }

    @Test
    fun `a present file with the kind applies its temperature and version`() {
        val calibrator = TemperatureCalibrator(
            CalibrationFile(key, "v7", mapOf("task.done" to KindCalibration(0.5, 0.4))),
        )

        val applied = calibrator.apply(Kinds.TASK_DONE, floatArrayOf(2f, 1f))

        assertFalse(applied.usedDefault)
        assertEquals("v7", applied.version)
        assertEquals(0.5, applied.temperature, 1e-12)
        assertEquals(0.4, applied.threshold, 1e-12)
        assertEquals(0.8807971, applied.probabilities[0], 1e-6)
    }

    @Test
    fun `a missing kind in a present file falls back to the conservative default`() {
        val calibrator = TemperatureCalibrator(
            CalibrationFile(key, "v7", mapOf("task.done" to KindCalibration(0.5, 0.4))),
        )

        val applied = calibrator.apply(Kinds.EFFECT_ACHIEVED, floatArrayOf(1f, 0f))

        assertTrue(applied.usedDefault)
        assertNull(applied.version)
        assertEquals(CONSERVATIVE_DEFAULT, KindCalibration(applied.temperature, applied.threshold))
        assertEquals(0.85, applied.threshold, 1e-12)
    }

    @Test
    fun `a mismatched calibration key is not found, so the defaults apply`() {
        val store = CalibrationStore(
            listOf(CalibrationFile(key, "v7", mapOf("task.done" to KindCalibration(0.5, 0.4)))),
        )

        val mismatched = CalibrationKey(BackendId.BONSAI_LOGPROB, "other-model", null, "logprob-v0")
        val applied = store.calibrator(mismatched).apply(Kinds.TASK_DONE, floatArrayOf(1f, 0f))

        assertTrue("a mismatched tuple must fall back", applied.usedDefault)
        assertEquals(0.85, applied.threshold, 1e-12)
        assertNull(applied.version)
    }

    @Test
    fun `an empty store is abstain-heavy by default`() {
        val applied = CalibrationStore().calibrator(key).apply(Kinds.ACTION_IRREVERSIBLE, floatArrayOf(1f, 0f))

        assertTrue(applied.usedDefault)
        assertEquals(0.85, applied.threshold, 1e-12)
        assertEquals(1.0, applied.temperature, 1e-12)
    }
}
