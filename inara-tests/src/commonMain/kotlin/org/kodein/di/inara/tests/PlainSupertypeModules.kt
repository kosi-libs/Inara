package org.kodein.di.inara.tests

import org.kodein.di.inara.ComposedDIModule
import org.kodein.di.inara.DIModule

/**
 * A plain (non-`@DIModule`) interface with one abstract and one default-bodied member, used to
 * verify a `@DIModule` interface may extend *any* interface, not just other `@DIModule` ones -
 * [Taggable]'s members are bound exactly like a `@DIModule` module's own.
 */
public interface Taggable {
    public val id: String
    public fun tag(): String = "tag:$id"
}

/** A generic plain interface, used with a concrete type argument to prove type substitution. */
public interface Converter<T> {
    public val default: T
    public fun convert(input: T): T
}

@DIModule
public interface TaggableModule : Taggable {
    public val extra: Int
}

@DIModule
public interface StringConverterModule : Converter<String> {
    public val prefix: String
}

/**
 * A `@ComposedDIModule` mixing a `@DIModule` parent ([CounterModule]) with a plain supertype
 * ([Taggable]): the parent is delegable (`CounterModule by counterModule`), the plain supertype is
 * not - [Taggable]'s members are bound directly, alongside [label].
 */
@ComposedDIModule
public interface TaggedCounterModule : CounterModule, Taggable {
    public val label: String
}
