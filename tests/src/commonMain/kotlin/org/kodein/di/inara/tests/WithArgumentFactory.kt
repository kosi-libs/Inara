package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory
import org.kodein.di.inara.DIModule

/**
 * Exercises `@DIFactory(with = …)` through real Gradle/KSP-generated code: the generated
 * `UserSessionDIFactory.UserSession(module, with)` extension takes a call-time [RequestContext]
 * alongside the module, and that value flows into the `argGreeting` extension function. `httpClient`
 * still resolves automatically off [NetworkModule.httpClient] with no `with` involved.
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
 * The automatic-resolution side of `with`: [RoutingModule.route] takes a single [Segment] the `with`
 * value casts into, so it resolves [Page.route] with no `arg` function - `PageDIFactory.Page(module,
 * with)` calls `module.route(with)`. [Segment] is a wrapper rather than a bare `String` so the `with` value is
 * not itself assignable to `Page.route` (which would then resolve straight from the call).
 */
data class Segment(val value: String)

@DIModule
interface RoutingModule {
    fun route(segment: Segment): String
}

class Page(val route: String)

@DIFactory(builds = Page::class, from = RoutingModule::class, with = Segment::class)
internal object PageDIFactory

/**
 * The `withNullable` variant: `BannerDIFactory.Banner(module, with)` takes a *nullable*
 * [RequestContext], so the generated parameter is `with: RequestContext?` and `argGreeting` receives
 * `null` when no context is passed. `httpClient` still resolves automatically with no `with` involved.
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

/**
 * The `with` value resolving a constructor argument directly: `Notice.context` has the same type as
 * the call-time value and no `arg` function, so `NoticeDIFactory.Notice(module, with)` passes `with`
 * straight into it. `httpClient` still resolves automatically off the module.
 */
class Notice(
    val context: RequestContext,
    val httpClient: HttpClient,
)

@DIFactory(builds = Notice::class, from = NetworkModule::class, with = RequestContext::class)
internal object NoticeDIFactory
