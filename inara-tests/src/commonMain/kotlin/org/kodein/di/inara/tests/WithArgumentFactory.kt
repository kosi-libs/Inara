package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory
import org.kodein.di.inara.DIModule

/**
 * Exercises `@DIFactory(with = …)` through real Gradle/KSP-generated code: the generated
 * `UserSession(module, with)` function takes a call-time [RequestContext] alongside the module, and
 * that value flows into the `argGreeting` extension function. `httpClient` still resolves
 * automatically off [NetworkModule.httpClient] with no `with` involved.
 */
data class RequestContext(val userId: String)

class UserSession(
    val httpClient: HttpClient,
    val greeting: String,
)

@DIFactory(builds = UserSession::class, from = NetworkModule::class, with = RequestContext::class)
internal object UserSessionDIFactory {
    fun NetworkModule.argGreeting(context: RequestContext): String = "Hello, ${context.userId}"
}

/**
 * The automatic-resolution side of `with`: [RoutingModule.route] takes a single `String` the `with`
 * value casts into, so it resolves [Page.route] with no `arg` function - `Page(module, with)` calls
 * `module.route(with)`.
 */
@DIModule
interface RoutingModule {
    fun route(segment: String): String
}

class Page(val route: String)

@DIFactory(builds = Page::class, from = RoutingModule::class, with = String::class)
internal object PageDIFactory

/**
 * The `withNullable` variant: `Banner(module, with)` takes a *nullable* [RequestContext], so the
 * generated parameter is `with: RequestContext?` and `argGreeting` receives `null` when no context
 * is passed. `httpClient` still resolves automatically with no `with` involved.
 */
class Banner(
    val httpClient: HttpClient,
    val greeting: String,
)

@DIFactory(builds = Banner::class, from = NetworkModule::class, withNullable = RequestContext::class)
internal object BannerDIFactory {
    fun NetworkModule.argGreeting(context: RequestContext?): String =
        context?.let { "Hello, ${it.userId}" } ?: "Hello, anonymous"
}
