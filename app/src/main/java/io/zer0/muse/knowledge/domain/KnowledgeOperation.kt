package io.zer0.muse.knowledge.domain

import kotlin.coroutines.cancellation.CancellationException

/** Recovery boundaries handle operational failures, never cancellation or VM errors. */
internal inline fun <T> knowledgeOperation(block: () -> T): Result<T> =
    runCatching(block).onFailure { failure ->
        if (failure is CancellationException || failure !is Exception) throw failure
    }
