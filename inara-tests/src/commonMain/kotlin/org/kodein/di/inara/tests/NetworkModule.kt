package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

public data class HttpClient(val baseUrl: String)

/**
 * Exercises every member shape the processor supports in a single module: a `val`, a zero-arg
 * `fun`, and a one-arg `fun`.
 */
@DIModule
public interface NetworkModule {
    public val baseUrl: String
    public fun httpClient(): HttpClient
    public fun endpoint(path: String): String
}
