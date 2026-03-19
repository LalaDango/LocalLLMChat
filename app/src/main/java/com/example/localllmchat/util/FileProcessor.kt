package com.example.localllmchat.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

sealed class ProcessedAttachment {
    data class TextAttachment(
        val fileName: String,
        val mimeType: String,
        val content: String,
        val wasTruncated: Boolean
    ) : ProcessedAttachment()

    data class ImageAttachment(
        val fileName: String,
        val mimeType: String,
        val base64Data: String,
        val wasResized: Boolean
    ) : ProcessedAttachment()
}

object FileProcessor {
    private const val MAX_TEXT_BYTES = 30 * 1024 // 30KB
    private const val MAX_IMAGE_DIMENSION = 1024

    fun processFile(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): ProcessedAttachment {
        return if (mimeType.startsWith("image/")) {
            processImage(contentResolver, uri, fileName, mimeType)
        } else {
            processTextFile(contentResolver, uri, fileName, mimeType)
        }
    }

    private fun processTextFile(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): ProcessedAttachment.TextAttachment {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open file: $fileName")

        inputStream.use { stream ->
            val bytes = stream.readBytes()
            val wasTruncated = bytes.size > MAX_TEXT_BYTES
            val effectiveBytes = if (wasTruncated) bytes.copyOf(MAX_TEXT_BYTES) else bytes
            val content = String(effectiveBytes, Charsets.UTF_8)
            return ProcessedAttachment.TextAttachment(fileName, mimeType, content, wasTruncated)
        }
    }

    private fun processImage(
        contentResolver: ContentResolver,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): ProcessedAttachment.ImageAttachment {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open image: $fileName")

        val originalBitmap = inputStream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Cannot decode image: $fileName")

        val longestSide = maxOf(originalBitmap.width, originalBitmap.height)
        val (bitmap, wasResized) = if (longestSide > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / longestSide
            val newWidth = (originalBitmap.width * scale).toInt()
            val newHeight = (originalBitmap.height * scale).toInt()
            Pair(
                Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true),
                true
            )
        } else {
            Pair(originalBitmap, false)
        }

        val outputStream = ByteArrayOutputStream()
        val compressFormat = if (mimeType == "image/png")
            Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        bitmap.compress(compressFormat, 85, outputStream)

        if (bitmap !== originalBitmap) bitmap.recycle()
        originalBitmap.recycle()

        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        return ProcessedAttachment.ImageAttachment(fileName, mimeType, base64, wasResized)
    }
}
