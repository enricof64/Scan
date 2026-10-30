package io.github.enricof64.scan

import android.content.Context
import java.io.File

/**
 * On-device OCR. This fork ships without ML Kit and without Play services,
 * so local OCR is not available yet: the bundled ML Kit text-recognition
 * model that powered this object is cut, and OCR comes back in a later
 * phase (server-side OCR first, then a bundled PaddleOCR model).
 *
 * [recognize] throws until then; the existing callers already degrade to an
 * empty result on failure.
 */
object PageOcr {

    suspend fun recognize(context: Context, pageFile: File): String =
        throw UnsupportedOperationException(
            "Local OCR is not available in this build (OCR returns in a later phase)"
        )
}
