package dev.operator.llm

import android.os.Parcel
import android.os.Parcelable

/**
 * §2.3 `labelLogits(req, role, promptParts, labelTokenIds)`: the token ids of one option label.
 * A list of these is passed so the surface has no nested arrays.
 */
data class LabelTokens(val tokenIds: IntArray) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeIntArray(tokenIds)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is LabelTokens && tokenIds.contentEquals(other.tokenIds))

    override fun hashCode(): Int = tokenIds.contentHashCode()

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LabelTokens> = object : Parcelable.Creator<LabelTokens> {
            override fun createFromParcel(source: Parcel): LabelTokens =
                LabelTokens(source.createIntArray() ?: IntArray(0))

            override fun newArray(size: Int): Array<LabelTokens?> = arrayOfNulls(size)
        }
    }
}
