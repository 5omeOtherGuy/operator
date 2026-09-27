package dev.operator.llm

import android.os.Parcel
import android.os.Parcelable

/** §2.3 `stats()` / `bench(pp, tg, threads)`; `cpuVariant` is the ggml CPU variant loaded from `nativeLibraryDir` (§4.1). */
data class LlmStats(
    val nPrompt: Int,
    val nGen: Int,
    val tPrefillMs: Long,
    val tDecodeMs: Long,
    val loadMs: Long,
    val ppTps: Double,
    val tgTps: Double,
    val backend: LlmBackend,
    val cpuVariant: String,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(nPrompt)
        dest.writeInt(nGen)
        dest.writeLong(tPrefillMs)
        dest.writeLong(tDecodeMs)
        dest.writeLong(loadMs)
        dest.writeDouble(ppTps)
        dest.writeDouble(tgTps)
        dest.writeString(backend.name)
        dest.writeString(cpuVariant)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LlmStats> = object : Parcelable.Creator<LlmStats> {
            override fun createFromParcel(source: Parcel): LlmStats = LlmStats(
                nPrompt = source.readInt(),
                nGen = source.readInt(),
                tPrefillMs = source.readLong(),
                tDecodeMs = source.readLong(),
                loadMs = source.readLong(),
                ppTps = source.readDouble(),
                tgTps = source.readDouble(),
                backend = LlmBackend.valueOf(source.readString().orEmpty()),
                cpuVariant = source.readString().orEmpty(),
            )

            override fun newArray(size: Int): Array<LlmStats?> = arrayOfNulls(size)
        }
    }
}
