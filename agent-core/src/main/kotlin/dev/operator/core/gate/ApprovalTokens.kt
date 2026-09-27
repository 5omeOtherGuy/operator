package dev.operator.core.gate

import dev.operator.core.api.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Process-local, per-start secret. Never persisted or exposed to the executor. */
class ApprovalTokens(
    private val random: SecureRandom = SecureRandom(),
) : ApprovalVerifier {
    private val key = ByteArray(32).also(random::nextBytes)
    private val outstanding = mutableSetOf<String>()

    @Synchronized
    fun mint(binding: ApprovalBinding, method: ApprovalMethod, nowMs: Long): ApprovalToken {
        val id = ByteArray(16).also(random::nextBytes).hex()
        val expires = nowMs + 30_000
        val token = ApprovalToken(id, binding.taskId, binding.step, binding.call,
            binding.screenSignature, binding.editedFieldContents.toMap(), method,
            nowMs, expires, mac(binding, id))
        outstanding.add(id)
        return token
    }

    @Synchronized
    override fun verifyAndConsume(token: ApprovalToken, current: ApprovalBinding, nowMs: Long): Boolean {
        if (token.id !in outstanding || nowMs < token.issuedAtMs || nowMs >= token.expiresAtMs ||
            token.expiresAtMs - token.issuedAtMs != 30_000L ||
            token.taskId != current.taskId || token.step != current.step ||
            token.call != current.call || token.screenSignature != current.screenSignature ||
            token.editedFieldContents != current.editedFieldContents
        ) return false
        val expected = mac(current, token.id)
        if (!MessageDigest.isEqual(expected.hexBytes(), token.mac.hexBytes())) return false
        outstanding.remove(token.id)
        return true
    }

    @Synchronized
    fun voidAll() = outstanding.clear()

    private fun mac(binding: ApprovalBinding, id: String): String {
        val data = bytes {
            writeUTF(binding.taskId)
            writeInt(binding.step)
            write(sha(bytes { encodeCall(binding.call) }))
            writeUTF(binding.screenSignature.packageName)
            writeUTF(binding.screenSignature.windowTitle ?: "")
            writeLong(binding.screenSignature.structuralHash)
            write(sha(bytes {
                binding.editedFieldContents.entries.sortedBy { it.key.hash }.forEach {
                    writeLong(it.key.hash)
                    writeUTF(it.value)
                }
            }))
            writeUTF(id)
        }
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data).hex()
    }
}

private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { out -> DataOutputStream(out).use(block) }.toByteArray()

/** Length-prefixed, sorted field encoding: not dependent on map iteration or data-class toString(). */
private fun DataOutputStream.encodeCall(call: ToolCall) {
    writeUTF(call.javaClass.name)
    call.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .sortedBy { it.name }.forEach { field ->
            field.isAccessible = true
            writeUTF(field.name)
            encodeValue(field.get(call))
        }
}

private fun DataOutputStream.encodeValue(value: Any?) {
    when (value) {
        null -> writeByte(0)
        is String -> { writeByte(1); writeUTF(value) }
        is Number -> { writeByte(2); writeUTF(value.toString()) }
        is Boolean -> { writeByte(3); writeBoolean(value) }
        is Enum<*> -> { writeByte(4); writeUTF(value.name) }
        is List<*> -> { writeByte(5); writeInt(value.size); value.forEach(::encodeValue) }
        is ElementKey -> { writeByte(6); writeLong(value.hash) }
        else -> error("Unsupported ToolCall argument: ${value.javaClass.name}")
    }
}

private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun String.hexBytes(): ByteArray = try {
    require(length == 64)
    ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
} catch (_: IllegalArgumentException) { byteArrayOf() }
