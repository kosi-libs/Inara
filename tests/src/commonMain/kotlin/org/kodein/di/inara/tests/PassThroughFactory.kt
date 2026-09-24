package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory

/**
 * A wrapper rather than a bare `String` - [NetworkModule] has no member returning [Nickname] or
 * [Note], so neither can ever resolve automatically (unlike `String`, which [NetworkModule.baseUrl]
 * would make an accidental exact/assignable match for).
 */
data class Nickname(val value: String)
data class Note(val value: String)

/**
 * Exercises a `@DIFactory` step-5 pass-through through real Gradle/KSP-generated code: `nickname`
 * and `note` resolve off neither [NetworkModule] nor the call-time [RequestContext], so the
 * generated `WelcomeDIFactory.Welcome(module, with, argNickname, argNote)` simply asks the caller
 * for them - named `arg<Argument>`, in constructor declaration order, after `module` and `with`.
 * `httpClient` and `greeting` still resolve as usual - automatically off
 * [NetworkModule.httpClient], and via `argGreeting` off the call-time [RequestContext] - so this
 * mixes every argument source `@DIFactory` has in one generated function.
 */
class Welcome(
    val httpClient: HttpClient,
    val greeting: String,
    val nickname: Nickname,
    val note: Note?,
)

@DIFactory(builds = Welcome::class, from = NetworkModule::class, with = RequestContext::class)
internal object WelcomeDIFactory {
    fun NetworkModule.argGreeting(context: RequestContext): String = "Hello, ${context.userId}"
}
