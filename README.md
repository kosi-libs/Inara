# Inara

**Compile-time dependency injection for Kotlin Multiplatform — your modules are just interfaces.**

Inara turns a plain Kotlin interface into a dependency-injection module. You declare *what* a module
offers as ordinary interface members; you decide *how* each member is produced by supplying a
`Binding` at construction time. A KSP processor generates the implementation.

```kotlin
@DIModule
interface NetworkModule {
    val baseUrl: String
    fun httpClient(): HttpClient
    fun endpoint(path: String): String
}

@DIFactory(builds = LoginController::class, from = NetworkModule::class)
object LoginControllerFactory

// Thanks to @DIModule
val network = NetworkModule(
    baseUrl = Binding.value("https://api.example.com"),
    httpClient = Binding.single { HttpClient(baseUrl) },
    endpoint = Binding.factory { path -> "$baseUrl/$path" },
)

val endpoint = network.endpoint("users")   // "https://api.example.com/users"

// Thanks to @DIFactory
val loginController = LoginController(network)
```

No reflection. No service locator. No runtime graph. Just an interface, a generated implementation,
and a compiler that tells you when a dependency is missing.

---

## Table of contents

- [Why Inara](#why-inara)
- [Setup](#setup)
- [Core concepts](#core-concepts)
- [Bindings reference](#bindings-reference)
- [Member shapes](#member-shapes)
- [Default implementations](#default-implementations)
- [Copying a module](#copying-a-module)
- [Inheritance and composition](#inheritance-and-composition)
- [Extending non-module interfaces](#extending-non-module-interfaces)
- [Ignored members](#ignored-members)
- [Visibility and nesting](#visibility-and-nesting)
- [Building plain classes](#building-plain-classes)
- [Generated code reference](#generated-code-reference)
- [Rules and diagnostics](#rules-and-diagnostics)
- [Thread safety](#thread-safety)
- [Supported platforms](#supported-platforms)
- [Project layout](#project-layout)
- [License](#license)

---

## Why Inara

**Your module *is* its own API.** A `@DIModule` interface is the contract consumers depend on.
There is no separate "component" type, no qualifier annotations, no `get<T>()` lookups. Consumers
call `module.httpClient()` — a normal method on a normal interface.

**Wiring is a value, not a configuration language.** A module instance is built by calling a
generated function with one `Binding` per member. Bindings are ordinary values you can pass around,
store, and swap.

**Every dependency is checked by the compiler.** Forget a binding and the builder call doesn't
compile. There is no runtime "unresolved dependency" failure mode.

**Bodies see the whole module.** A `Binding`'s lambda runs with the module as its receiver, so any
member can reach any sibling member — including inherited ones — without you wiring anything up:

```kotlin
Binding.single { HttpClient(baseUrl) }   // `baseUrl` is this module's own member
```

**`suspend` members are native.** Mark a member `suspend` and it is bound with a `SuspendBinding`,
whose every factory takes a suspending body. The generated override is itself `suspend`, so
asynchronous construction propagates the way it should instead of hiding behind `runBlocking` or a
`Deferred` you have to unwrap at every call site.

```kotlin
suspend fun token(): Token   // bound with SuspendBinding.single { authClient.fetchToken() }
```

**Overriding is first-class.** Every module gets generated `copy(...)` overloads, so building a test
variant means overriding one member and inheriting the rest.

---

## Setup

Inara ships two artifacts:

| Artifact | Purpose |
| --- | --- |
| `org.kodein.di.inara:inara-runtime` | The `Binding` types and annotations. A normal dependency. |
| `org.kodein.di.inara:inara-processor` | The KSP processor that generates implementations. |

### Kotlin Multiplatform

Declare your modules in `commonMain` and run the processor once over the common metadata source set,
then feed the generated sources back into `commonMain`:

```kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm()
    iosArm64()
    // ...your targets

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            dependencies {
                implementation("org.kodein.di.inara:inara-runtime:0.1.0")
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", "org.kodein.di.inara:inara-processor:0.1.0")
}

// Every compilation - and every per-target KSP task, which also reads commonMain as part of its
// merged source set even though it has no processor registered - consumes the KSP-generated
// commonMain sources, so all of them must wait for KSP to run.
tasks.matching { it.name != "kspCommonMainKotlinMetadata" }.configureEach {
    if (name.startsWith("ksp") || this is KotlinCompilationTask<*>) {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
```

### JVM-only

```kotlin
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("org.kodein.di.inara:inara-runtime:0.1.0")
    ksp("org.kodein.di.inara:inara-processor:0.1.0")
}
```

---

## Core concepts

### The module interface

Annotate an interface with `@DIModule`. Every member becomes a slot to be bound:

```kotlin
@DIModule
interface AppModule {
    val config: Config           // Binding<AppModule, Unit, Config>
    fun repository(): Repository // Binding<AppModule, Unit, Repository>  - same shape as above
    fun user(id: Int): User      // Binding<AppModule, Int, User>         - takes an argument
}
```

**A property and a zero-argument function are the same thing to Inara.** Both produce a
`Binding<AppModule, Unit, …>`; choosing between them is purely about how you want the call site to
read. The only thing a member's declaration decides is its *argument* type — no parameters means
`Unit`, parameters are passed through to the binding (see [Member shapes](#member-shapes)).

What a declaration does **not** decide is the member's lifecycle. Nothing about `val` implies a
stored constant, and nothing about `fun` implies recomputation on every call — that is entirely the
binding's job, and every choice is open to both:

```kotlin
AppModule(
    config = Binding.provider { loadConfig() },  // a `val`, recomputed on every read
    repository = Binding.value(FixedRepository), // a `fun`, returning a constant
    user = Binding.factory { id -> User(id) },
)
```

### The generated builder

The processor generates a function with the same name as the interface, taking one parameter per
member:

```kotlin
public fun AppModule(
    config: Binding<AppModule, Unit, Config>,
    repository: Binding<AppModule, Unit, Repository>,
    user: Binding<AppModule, Int, User>,
): AppModule
```

Because the parameters are named after the members, call sites read like the interface itself:

```kotlin
val app = AppModule(
    config = Binding.value(Config(debug = true)),
    repository = Binding.single { SqlRepository(config) },
    user = Binding.factory { id -> repository().findUser(id) },
)
```

### `Binding<M, A, T>`

A binding is a recipe for producing a `T`:

```kotlin
public interface Binding<M, A, T> {
    public fun get(module: M, arg: A): T
}
```

- `M` — the module type. It is always the *leaf* module the binding is being supplied to, which is
  what lets a body reach any sibling member through its receiver.
- `A` — the argument type: `Unit` for a property or a no-argument function, otherwise derived from
  the member's parameters (see [Member shapes](#member-shapes)).
- `T` — the produced type.

`SuspendBinding<M, A, T>` is the identical shape with a `suspend fun get`, used automatically for
`suspend` members.

---

## Bindings reference

All factories live on the `Binding` companion (and mirror onto `SuspendBinding`).

| Factory                  | Signature                    | Behaviour                                                    |
|--------------------------|------------------------------|--------------------------------------------------------------|
| `value(v)`               | `T`                          | Always returns the same pre-built `v`. Nothing is computed.  |
| `provider { }`           | `M.() -> T`                  | Runs the body on **every** access.                           |
| `factory { }`            | `M.(A) -> T`                 | Runs the body on every access, with the caller's argument.   |
| `single(safety) { }`     | `M.() -> T`                  | Runs the body **once**, memoizes the result forever.         |
| `weakSingle(safety) { }` | `M.() -> T`, `T : Any`       | Memoizes behind a weak reference; rebuilds after collection. |
| `multiplex { }`          | `(A) -> Binding<M, Unit, T>` | Builds and caches one sub-binding **per argument value**.    |

```kotlin
Binding.value(Config(debug = true))
Binding.provider { System.currentTimeMillis() }
Binding.factory { id -> User(id) }
Binding.single { SqlRepository(config) }
Binding.weakSingle { ExpensiveCache() }
Binding.multiplex { key -> Binding.single { Connection(key) } }
```

### `multiplex` — per-argument lifecycles

`multiplex` is how you give an argument-taking member a lifecycle. On its own, `factory` recomputes
every call. Wrapping a `single` in a `multiplex` gives you *one instance per distinct argument*:

```kotlin
// One Connection per host, each built once, cached forever.
Binding.multiplex { host -> Binding.single { Connection(host) } }
```

### `suspend` members

A `suspend` member gets a `SuspendBinding` automatically:

```kotlin
@DIModule
interface AsyncModule {
    suspend fun load(): Service
    suspend fun loadKeyed(key: String): Service
}

AsyncModule(
    load = SuspendBinding.single { fetchService() },
    loadKeyed = SuspendBinding.factory { key -> fetchService(key) },
)
```

`SuspendBinding` mirrors every factory above. Two differences: its `single` and `weakSingle` take no
`safety` parameter (they always use a coroutine `Mutex`), and its bodies are `suspend`.

An existing non-suspend `Binding` can be adapted with `.suspend()`:

```kotlin
import org.kodein.di.inara.suspend   // required: `suspend` is a soft keyword, so it is not auto-imported

val binding: Binding<M, Unit, T> = Binding.value(x)
val suspending: SuspendBinding<M, Unit, T> = binding.suspend()
```

---

## Member shapes

The member's parameters determine the binding's argument type `A`:

| Member                          | Binding type                            |
|---------------------------------|-----------------------------------------|
| `val name: T`                   | `Binding<M, Unit, T>`                   |
| `fun make(): T`                 | `Binding<M, Unit, T>`                   |
| `fun make(id: Int): T`          | `Binding<M, Int, T>`                    |
| `fun make(vararg s: String): T` | `Binding<M, Array<out String>, T>`      |
| `fun make(vararg i: Int): T`    | `Binding<M, IntArray, T>`               |
| `fun make(a: A, b: B): T`       | `Binding<M, MBindingArguments.Make, T>` |
| `suspend fun …`                 | the same, as `SuspendBinding`           |

### Multiple parameters

`Binding` carries exactly one argument. When a member takes two or more parameters, the processor
generates a `<Module>BindingArguments` container holding one `data class` per such member, named
after the member capitalized:

```kotlin
@DIModule
interface Module {
    fun va(vararg str: String): String
    fun mult(s: List<String>, i: Int): Long
    fun both(vararg ids: Int, flag: Boolean = false): String
}
```

generates

```kotlin
public class ModuleBindingArguments private constructor() {
    public data class Mult(val s: List<String>, val i: Int)

    @Suppress("ArrayInDataClass")
    public data class Both(val ids: IntArray, val flag: Boolean)
}
```

and you bind against those:

```kotlin
Module(
    va = Binding.factory { parts -> parts.joinToString("-") },
    mult = Binding.factory { args -> args.s.size.toLong() + args.i },
    both = Binding.factory { args -> "${args.ids.toList()}:${args.flag}" },
)
```

---

## Default implementations

A member with a body in the interface gets an **optional**, nullable binding that defaults to `null`,
meaning "keep the interface's own implementation":

```kotlin
@DIModule
interface DefaultsModule {
    val name: String
    val greeting: String get() = "Hello, $name"
    fun shout(): String = "$greeting!!!"
}
```

```kotlin
// Defaults kept - only the abstract member must be bound.
val m = DefaultsModule(name = Binding.value("World"))
m.greeting   // "Hello, World"
m.shout()    // "Hello, World!!!"

// Any default can still be overridden.
val n = DefaultsModule(name = Binding.value("World"), greeting = Binding.value("Hi $name"))
n.shout()    // "Hi World!!!"  - shout()'s default body re-reads the overridden greeting
```

Default bodies are re-evaluated against the *built* module, so a default that reads a sibling member
observes whatever that sibling was bound to.

---

## Copying a module

Every module gets a `copy` extension function that takes one parameter per member, each defaulting to the module's *current* behaviour,
so you override exactly what you name:

```kotlin
val testModule = appModule.copy(
    repository = Binding.value(FakeRepository()),
)
```

Members with a default implementation behave slightly differently under `copy`: their parameter still
defaults to `null` (meaning "run the interface's default body") rather than freezing the current
value. That is deliberate — the default body is re-run against the *new* module, so it observes
siblings you overrode in the very same `copy` call.

---

## Inheritance and composition

### `@DIModule` inheritance — flattening

A `@DIModule` interface may extend other `@DIModule` interfaces. Inherited members are bound exactly
as if declared directly on the child:

```kotlin
@DIModule interface GreeterModule { val greeting: String }
@DIModule interface CounterModule { fun next(): Int }

@DIModule
interface AppModule : GreeterModule, CounterModule {
    val label: String
}

AppModule(
    greeting = Binding.value("Hi"),   // inherited
    next = Binding.provider { 0 },    // inherited
    label = Binding.value("app"),     // own
)
```

Every binding is parameterized on the leaf module (`Binding<AppModule, …>`), so an inherited member's
body can reach members declared on the child.

Diamond inheritance is allowed here: a shared ancestor reached through two paths contributes its
members exactly once.

### `composed = true` — delegation

When you want to build a module from *already-built* parent modules rather than rebinding their
members, pass `composed = true` to `@DIModule`. It additionally generates a delegating
implementation:

```kotlin
@DIModule(composed = true)
interface AppModule : GreeterModule, CounterModule {
    val label: String
}
```

You now get **two** builders:

```kotlin
// 1. Delegated: pass built parents, bind only the child's own members.
val app = AppModule(
    greeterModule = greeter,        // an existing GreeterModule instance
    counterModule = counter,        // an existing CounterModule instance
    label = Binding.value("app"),
)

// 2. Full: bind every member individually (identical to plain @DIModule).
val app2 = AppModule(
    greeting = Binding.value("Hi"),
    next = Binding.provider { 0 },
    label = Binding.value("app"),
)
```

…and an extra `copy` overload that swaps out a whole parent at once:

```kotlin
val withOtherGreeter = app.copy(greeterModule = differentGreeter)
```

Parent parameter names are derived from the parent's name, decapitalized (`GreeterModule` →
`greeterModule`).

**Diamond inheritance is rejected for `composed = true`.** Two delegates both supplying the same
ancestor's members is ambiguous in Kotlin, and the generator does not attempt to disambiguate it.
Plain `@DIModule` has no such constraint.

---

## Extending non-module interfaces

A `@DIModule` interface may extend **any** interface, not just annotated ones. Members reachable
through a plain supertype are bound exactly like the module's own:

```kotlin
interface Taggable {
    val id: String
    fun tag(): String = "tag:$id"
}

@DIModule
interface TaggableModule : Taggable {
    val extra: Int
}

TaggableModule(
    id = Binding.value("x1"),   // from Taggable - abstract, so required
    extra = Binding.value(7),
    // `tag` is also from Taggable, but it has a default body, so its binding is optional
)
```

Generic supertypes work too — type arguments are substituted:

```kotlin
interface Converter<T> {
    val default: T
    fun convert(input: T): T
}

@DIModule
interface StringConverterModule : Converter<String> { val prefix: String }

// convert is bound as Binding<StringConverterModule, String, String>
```

A plain supertype is never *delegable*: under `@DIModule(composed = true)` its members appear as own
bindings in the delegated builder, alongside the module's own declarations.

---

## Ignored members

Three member shapes cannot be represented by a `Binding` at all — it has no setter, no extension
receiver, and its type arguments are fixed per instance:

- `var` properties
- extension members
- generic functions

If such a member **has a default implementation**, it is silently excluded from the module's bindable
surface: no `Binding` parameter, no override in the generated class. Ordinary Kotlin inheritance
resolves it to the interface's own body.

```kotlin
@DIModule
interface Module {
    val name: String                                  // bound normally

    var tag: String                                   // ignored: both accessors defaulted
        get() = "default-tag"
        set(_) {}

    val String.shoutedLength: Int                     // ignored: extension with a body
        get() = uppercase().length

    fun <T> identity(value: T): T = value              // ignored: generic with a body
}

val m = Module(name = Binding.value("x"))   // only `name` is a parameter
m.tag                                        // "default-tag"
m.identity(42)                               // 42
```

Without a default implementation these are **compile errors** — there would be no way to satisfy
them. A `var` needs *both* accessors implemented to qualify; one abstract accessor still leaves
something unsatisfiable.

---

## Visibility and nesting

An `internal` module interface gets an `internal` builder and `internal` `copy` functions:

```kotlin
@DIModule
internal interface InternalModule { val secret: String }
```

A nested module interface is supported. The generated implementation class flattens the enclosing
scopes to avoid collisions, while the builder keeps the interface's simple name:

```kotlin
interface Outer {
    @DIModule
    interface Inner { val label: String }
}

val inner: Outer.Inner = Inner(label = Binding.value("inner"))
// generated: private class GeneratedOuterInner(...) : Outer.Inner
```

---

## Building plain classes

Everything so far builds a `@DIModule` interface itself. `@DIFactory` builds something else with it:
a plain constructor-shaped function for a class that never references Inara at all. Annotate an
`internal object` with `builds`/`from`, and the processor generates a top-level function that
resolves every constructor argument off a module instance:

```kotlin
class UsersController(val httpClient: HttpClient, val endpoint: String)

@DIFactory(builds = UsersController::class, from = NetworkModule::class)
internal object UserControllerDIFactory {
    fun NetworkModule.argEndpoint(): String = endpoint("users")
}
```

generates

```kotlin
public fun UsersController(module: NetworkModule): UsersController = with(UserControllerDIFactory) {
    UsersController(
        httpClient = module.httpClient(),
        endpoint = module.argEndpoint(),
    )
}
```

`UsersController` itself has no idea Inara exists — call the generated function like any other:

```kotlin
val controller = UsersController(network)   // `network` is a built NetworkModule
```

### Resolving constructor arguments

Each constructor argument is resolved independently, in priority order:

1. An `arg<Argument>` extension function declared on the annotated object — `fun
   NetworkModule.argEndpoint(): String` resolves an argument named `endpoint`. This is the *only*
   thing a `@DIFactory` object may declare; anything else is a compile error.
2. Otherwise, a uniquely-typed member of `from` — `httpClient` above resolves automatically because
   `NetworkModule.httpClient()` is the only member returning `HttpClient`, exactly.
3. Failing an exact match, a uniquely-typed *assignable* (supertype) member.
4. Otherwise, a compile error recommending the `arg` function to add.

Because `arg<Argument>` is a reserved naming convention, a `@DIModule` member itself may not be named
`arg` followed by an uppercase letter.

### `suspend` arguments

By default (`Suspend.Allow`), a `suspend` provider — an `arg` function or a `suspend` module member —
is just as eligible as a non-`suspend` one, and the generated function itself becomes `suspend` if
and only if an argument actually resolved to one:

```kotlin
class AsyncGreeter(val service: Service)

@DIFactory(builds = AsyncGreeter::class, from = AsyncModule::class)
internal object AsyncGreeterDIFactory
```

```kotlin
// generated:
public suspend fun AsyncGreeter(module: AsyncModule): AsyncGreeter = with(AsyncGreeterDIFactory) {
    AsyncGreeter(
        service = module.load(),   // AsyncModule.load() is a suspend member
    )
}
```

`suspend = Suspend.Forbid` excludes every `suspend` candidate from resolution entirely — a
`suspend`-only argument becomes unresolvable rather than silently skipped — and the generated
function is never `suspend`. `suspend = Suspend.Force` resolves exactly like `Allow` but always
generates a `suspend` function, whether or not any provider actually is.

### `constructorIndex` — building through a non-primary constructor

By default `@DIFactory` calls `builds`'s primary constructor. `constructorIndex` selects a different
one instead, in declaration order (`0` the primary, `1` the first secondary, `2` the second, …) —
useful when the intended entry point isn't the primary constructor, e.g. a `private` primary paired
with a public secondary:

```kotlin
class Greeter private constructor(val greeting: String, private val marker: Int) {
    constructor(baseUrl: String) : this("Hello from $baseUrl", 0)
}

@DIFactory(builds = Greeter::class, from = NetworkModule::class, constructorIndex = 1)
internal object GreeterDIFactory
// generated: public fun Greeter(module: NetworkModule): Greeter = with(GreeterDIFactory) {
//                Greeter(baseUrl = module.baseUrl)
//            }
```

Only the selected constructor's parameters are resolved — every other constructor, including the
primary, is ignored entirely, `arg<Argument>` name-matching included.

---

## Thread safety

`Binding.single` and `Binding.weakSingle` take a `SingleThreadSafety` mode:

| Mode | Guarantee |
| --- | --- |
| `Synchronized` (default) | The builder runs **exactly once**, under a lock. |
| `Published` | Lock-free. Concurrent callers may each run the builder, but all callers converge on a single winning instance. |

```kotlin
Binding.single(SingleThreadSafety.Published) { ExpensiveButPureValue() }
```

`multiplex` isolates arguments: concurrent access with distinct arguments never contends.

`weakSingle` holds its value behind a platform weak reference and rebuilds on the next access
after collection.

`SuspendBinding.single` / `weakSingle` always use a coroutine `Mutex` and take no mode parameter (coroutines mutex are very lightweight).

---

## Supported platforms

`inara-runtime` is a Kotlin Multiplatform library targeting:

- **JVM**
- **iOS** — `iosArm64`, `iosSimulatorArm64`, `iosX64`
- **watchOS** — `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64`
- **tvOS** — `tvosArm64`, `tvosSimulatorArm64`
- **macOS** — `macosArm64`
- **Android Native** — `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86`
- **Linux** — `linuxX64`, `linuxArm64`
- **Windows** — `mingwX64`
- **JS** — browser, Node.js
- **Wasm/JS** — browser, Node.js

---

## Project layout

| Module | Contents |
| --- | --- |
| `inara-runtime` | `Binding`, `SuspendBinding`, `@DIModule`, `@DIFactory`. Multiplatform, `explicitApi()` enabled. |
| `inara-processor` | The KSP `SymbolProcessor`: `ModuleModel.kt` (parsing/validation) and `ModuleFileGenerator.kt` (KotlinPoet generation). |
| `inara-tests` | Integration tests running the real processor through Gradle/KSP across every target. |

### Building

```bash
./gradlew build                  # everything, all targets
./gradlew :inara-processor:test  # processor golden-source + validation tests
./gradlew :inara-tests:jvmTest   # real KSP round-trip
```

Processor tests compile sources in-memory with
[kotlin-compile-testing](https://github.com/ZacSweers/kotlin-compile-testing) and assert on the
generated output; `inara-tests` exercises the same processor through a real Gradle KSP run.

---

## License

Inara is released under the [MIT License](LICENSE).

```
Copyright (c) 2026 Salomon BRYS
```
