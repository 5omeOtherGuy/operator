package dev.operator.llm

import android.os.Parcel
import android.os.Parcelable

/** §4.6: sampling parameters. Greedy for grammar-constrained calls; the vendor defaults (0.5/20/0.85) for free text. */
data class Sampling(
    val greedy: Boolean = true,
    val temperature: Float = 0.5f,
    val topK: Int = 20,
    val topP: Float = 0.85f,
    val minP: Float = 0.0f,
    val seed: Int = 0,
)

/**
 * §2.3: the `req` of `generate`, `labelLogits` and `embedLast`, and the correlator `abort(req)` uses.
 * `sequenceId` 0 is the task sequence, >0 a decide branch (`llama_memory_seq_cp`, §4.5).
 */
data class GenerateRequest(
    val id: Long,
    val sampling: Sampling,
    val sequenceId: Int,
    val reusePrefix: Boolean,
    val grammarFirst: Boolean,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeInt(if (sampling.greedy) 1 else 0)
        dest.writeFloat(sampling.temperature)
        dest.writeInt(sampling.topK)
        dest.writeFloat(sampling.topP)
        dest.writeFloat(sampling.minP)
        dest.writeInt(sampling.seed)
        dest.writeInt(sequenceId)
        dest.writeInt(if (reusePrefix) 1 else 0)
        dest.writeInt(if (grammarFirst) 1 else 0)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GenerateRequest> = object : Parcelable.Creator<GenerateRequest> {
            override fun createFromParcel(source: Parcel): GenerateRequest = GenerateRequest(
                id = source.readLong(),
                sampling = Sampling(
                    greedy = source.readInt() != 0,
                    temperature = source.readFloat(),
                    topK = source.readInt(),
                    topP = source.readFloat(),
                    minP = source.readFloat(),
                    seed = source.readInt(),
                ),
                sequenceId = source.readInt(),
                reusePrefix = source.readInt() != 0,
                grammarFirst = source.readInt() != 0,
            )

            override fun newArray(size: Int): Array<GenerateRequest?> = arrayOfNulls(size)
        }
    }
}
