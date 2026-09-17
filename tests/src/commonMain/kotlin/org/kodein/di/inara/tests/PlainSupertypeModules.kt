package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * A plain (non-`@DIModule`) interface with one abstract and one default-bodied member, used to
 * verify a `@DIModule` interface may extend *any* interface, not just other `@DIModule` ones -
 * [Taggable]'s members are bound exactly like a `@DIModule` module's own.
 */
interface Taggable {
    val id: String
    fun tag(): String = "tag:$id"
}

/** A generic plain interface, used with a concrete type argument to prove type substitution. */
interface Converter<T> {
    val default: T
    fun convert(input: T): T
}

@DIModule
interface TaggableModule : Taggable {
    val extra: Int
}

@DIModule
interface StringConverterModule : Converter<String> {
    val prefix: String
}

/**
 * A `@DIModule(composed = true)` mixing a `@DIModule` parent ([CounterModule]) with a plain supertype
 * ([Taggable]): the parent is delegable (`CounterModule by counterModule`), the plain supertype is
 * not - [Taggable]'s members are bound directly, alongside [label].
 */
@DIModule(composed = true)
interface TaggedCounterModule : CounterModule, Taggable {
    val label: String
}
