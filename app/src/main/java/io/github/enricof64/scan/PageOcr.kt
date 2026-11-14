package io.github.enricof64.scan

import android.content.Context
import io.github.enricof64.scan.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device OCR entry point for the existing callers (page "copy text").
 *
 * Backed by [OcrEngine] — PP-OCRv5 mobile (det + rec) on the bundled
 * onnxruntime; unlimited and offline. [recognize] returns the recognized
 * text (lines joined by newlines) in reading order; on failure it returns an
 * empty string, the contract the callers already rely on.
 */
object PageOcr {

    suspend fun recognize(context: Context, pageFile: File): String =
        withContext(Dispatchers.IO) {
            OcrEngine.recognizeFile(context, pageFile)?.plainText ?: ""
        }
}
