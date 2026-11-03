package io.zer0.muse.ui

import io.zer0.common.FileSizeFormatter

/** Formatee bytes a un tamaño de archivo legible por humanos(B / KB / MB). */
object DebugFormatters {
    fun formatFileSize(bytes: Long): String = FileSizeFormatter.format(bytes)
}
