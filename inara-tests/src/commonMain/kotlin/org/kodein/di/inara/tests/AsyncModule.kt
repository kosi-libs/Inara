package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Exercises `suspend` members through real Gradle/KSP-generated code: a `suspend fun` with no
 * argument, one with an argument, a `suspend fun` with a default body, and a plain (non-suspend)
 * member alongside them to prove the two binding kinds coexist in one generated class.
 */
@DIModule
public interface AsyncModule {
    public val name: String
    public suspend fun load(): Service
    public suspend fun loadKeyed(key: String): Service
    public suspend fun described(): String = "$name: ${load().id}"
}
