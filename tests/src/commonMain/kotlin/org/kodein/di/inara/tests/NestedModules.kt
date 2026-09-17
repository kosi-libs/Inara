package org.kodein.di.inara.tests

import org.kodein.di.inara.DIModule

/**
 * Proves that a `@DIModule` interface nested inside another declaration gets its generated file
 * and class named after every enclosing scope flattened together (`InaraOuterInner`).
 */
interface Outer {
    @DIModule
    interface Inner {
        val label: String
    }
}
