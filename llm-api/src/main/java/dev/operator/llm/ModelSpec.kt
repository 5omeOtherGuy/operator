package dev.operator.llm

import android.os.Parcel
import android.os.Parcelable

/** §2.3 `load(ModelSpec{path, sha256, role, nCtx, kvType, backend, threads, useExtraBufts})`; sha256 is the `modelId` (§4.8). */
data class ModelSpec(
    val path: String,
    val sha256: String,
    val role: ModelRole,
    val nCtx: Int,
    val kvType: KvType,
    val backend: LlmBackend,
    val threads: Int,
    val useExtraBufts: Boolean,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(path)
        dest.writeString(sha256)
        dest.writeString(role.name)
        dest.writeInt(nCtx)
        dest.writeString(kvType.name)
        dest.writeString(backend.name)
        dest.writeInt(threads)
        dest.writeInt(if (useExtraBufts) 1 else 0)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ModelSpec> = object : Parcelable.Creator<ModelSpec> {
            override fun createFromParcel(source: Parcel): ModelSpec = ModelSpec(
                path = source.readString().orEmpty(),
                sha256 = source.readString().orEmpty(),
                role = ModelRole.valueOf(source.readString().orEmpty()),
                nCtx = source.readInt(),
                kvType = KvType.valueOf(source.readString().orEmpty()),
                backend = LlmBackend.valueOf(source.readString().orEmpty()),
                threads = source.readInt(),
                useExtraBufts = source.readInt() != 0,
            )

            override fun newArray(size: Int): Array<ModelSpec?> = arrayOfNulls(size)
        }
    }
}
