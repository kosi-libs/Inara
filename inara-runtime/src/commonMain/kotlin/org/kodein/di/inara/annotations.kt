package org.kodein.di.inara

import kotlin.annotation.AnnotationRetention.SOURCE

@Retention(SOURCE)
@Target(AnnotationTarget.CLASS)
public annotation class DIModule

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalDIBindings
