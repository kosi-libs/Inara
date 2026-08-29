package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory
import org.kodein.di.inara.DIModule

/**
 * Exercises `@DIFactory(with = …)` through real Gradle/KSP-generated code: the generated
 * `UserSession(module, with)` function takes a call-time [RequestContext] alongside the module, and
 * that value flows into the `argGreeting` extension function. `httpClient` still resolves
 * automatically off [NetworkModule.httpClient] with no `with` involved.
 */
public data class RequestContext(val userId: String)

public class UserSession(
    public val httpClient: HttpClient,
    public val greeting: String,
)

@DIFactory(builds = UserSession::class, from = NetworkModule::class, with = RequestContext::class)
internal object UserSessionDIFactory {
    public fun NetworkModule.argGreeting(context: RequestContext): String = "Hello, ${context.userId}"
}

/**
 * The automatic-resolution side of `with`: [RoutingModule.route] takes a single `String` the `with`
 * value casts into, so it resolves [Page.route] with no `arg` function - `Page(module, with)` calls
 * `module.route(with)`.
 */
@DIModule
public interface RoutingModule {
    public fun route(segment: String): String
}

public class Page(public val route: String)

@DIFactory(builds = Page::class, from = RoutingModule::class, with = String::class)
internal object PageDIFactory
