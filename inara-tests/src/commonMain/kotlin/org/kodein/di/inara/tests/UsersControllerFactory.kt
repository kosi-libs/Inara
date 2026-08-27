package org.kodein.di.inara.tests

import org.kodein.di.inara.DIFactory

/**
 * Exercises `@DIFactory` through real Gradle/KSP-generated code: [UsersController] never
 * references Inara at all - only [UserControllerDIFactory] does. `httpClient` resolves
 * automatically (an exact-type match on [NetworkModule.httpClient]); `endpoint` needs the
 * `argEndpoint` extension function since no module member alone determines the path.
 */
public class UsersController(
    public val httpClient: HttpClient,
    public val endpoint: String,
)

@DIFactory(builds = UsersController::class, from = NetworkModule::class)
internal object UserControllerDIFactory {
    public fun NetworkModule.argEndpoint(): String = endpoint("users")
}
