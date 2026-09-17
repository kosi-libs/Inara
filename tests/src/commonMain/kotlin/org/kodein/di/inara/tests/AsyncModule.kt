package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Exercises `suspend` members through real Gradle/KSP-generated code: a `suspend fun` with no
 * argument, one with an argument, a `suspend fun` with a default body, and a plain (non-suspend)
 * member alongside them to prove the two binding kinds coexist in one generated class.
 */
@DIModule
interface AsyncModule {
    val name: String
    suspend fun load(): Service
    suspend fun loadKeyed(key: String): Service
    suspend fun described(): String = "$name: ${load().id}"
}
