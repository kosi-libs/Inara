package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

data class HttpClient(val baseUrl: String)

/**
 * Exercises every member shape the processor supports in a single module: a `val`, a zero-arg
 * `fun`, and a one-arg `fun`.
 */
@DIModule
interface NetworkModule {
    val baseUrl: String
    fun httpClient(): HttpClient
    fun endpoint(path: String): String
}
