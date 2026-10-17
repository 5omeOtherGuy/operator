package dev.operator.llm

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The IDs in ggml.h at the pinned llama.cpp revision. Q2_0 (42) is g128, not g64. */
object ModelGuards {
    private val bonsai = setOf(0, 41)
    private val clm = setOf(0, 1, 8, 10, 12, 14)

    fun allowed(types: Collection<Int>, clmEncoder: Boolean, verifiedQ2G64: Boolean = false): Boolean =
        types.isNotEmpty() && types.all {
            it in (if (clmEncoder) clm else bonsai) || (it == 42 && verifiedQ2G64 && !clmEncoder)
        }

    fun commonPrefix(a: List<Int>, b: List<Int>): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    fun sha256Matches(file: File, expected: String): Boolean {
        if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
    }

    /** Read only the GGUF directory; tensor data itself can be multi-GB. Fail closed on bad metadata. */
    fun tensorTypes(file: File): List<Int> = RandomAccessFile(file, "r").use { f ->
        fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()))
        fun u64(): Long = java.lang.Long.reverseBytes(f.readLong()).also {
            require(it >= 0) { "GGUF count overflow" }
        }
        fun skip(n: Long) {
            require(n >= 0 && n <= f.length() - f.filePointer) { "GGUF field out of range" }
            f.seek(f.filePointer + n)
        }
        fun str() {
            val n = u64()
            skip(n)
        }
        fun value(type: Int, depth: Int) {
            require(depth < 8) { "GGUF array nesting" }
            when (type) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                8 -> str()
                9 -> {
                    val element = u32().toInt()
                    val size = u64()
                    require(size <= 10_000_000) { "GGUF array too large" }
                    repeat(size.toInt()) { value(element, depth + 1) }
                }
                else -> error("GGUF unknown value type: $type")
            }
        }
        require(u32() == 0x46554747L) { "not GGUF" }
        require(u32() in 2L..3L) { "unsupported GGUF version" }
        val tensors = u64()
        val metadata = u64()
        require(tensors in 1..1_000_000 && metadata <= 1_000_000)
        repeat(metadata.toInt()) { str(); value(u32().toInt(), 0) }
        List(tensors.toInt()) {
            str()
            val dims = u32()
            require(dims in 1..4)
            repeat(dims.toInt()) { u64() }
            val type = u32().toInt()
            u64() // tensor offset
            type
        }
    }
}

object PayloadGuard {
    const val LIMIT = 256 * 1024
    fun check(vararg texts: String) {
        // Include a conservative allowance for parcel string lengths, field headers and arrays.
        require(64L + texts.sumOf { it.toByteArray(Charsets.UTF_16LE).size.toLong() + 16L } < LIMIT) {
            "binder payload exceeds 256 KB"
        }
    }
    fun checkArray(count: Int, bytesPerItem: Int) {
        require(count >= 0 && 64L + count.toLong() * bytesPerItem < LIMIT) {
            "binder payload exceeds 256 KB"
        }
    }
}
