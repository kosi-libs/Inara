package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Proves that a `@DIModule` interface nested inside another declaration gets its generated file
 * and class named after every enclosing scope flattened together (`GeneratedOuterInner`).
 */
public interface Outer {
    @DIModule
    public interface Inner {
        public val label: String
    }
}
