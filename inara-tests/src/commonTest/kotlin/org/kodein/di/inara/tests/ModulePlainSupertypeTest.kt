package org.kodein.di.inara.tests

import org.kodein.di.inara.Binding
import kotlin.test.Test
import kotlin.test.assertEquals

class ModulePlainSupertypeTest {
    @Test
    fun abstractMemberOfPlainSupertype_isBound() {
        val module = TaggableModule(
            id = Binding.value("x1"),
            extra = Binding.value(7),
        )

        assertEquals("x1", module.id)
        assertEquals(7, module.extra)
    }

    @Test
    fun defaultBodyMemberOfPlainSupertype_usesInterfaceBodyWhenOmitted() {
        val module = TaggableModule(
            id = Binding.value("x2"),
            extra = Binding.value(0),
        )

        assertEquals("tag:x2", module.tag())
    }

    @Test
    fun defaultBodyMemberOfPlainSupertype_canBeOverridden() {
        val module = TaggableModule(
            id = Binding.value("x3"),
            tag = Binding.value("custom"),
            extra = Binding.value(0),
        )

        assertEquals("custom", module.tag())
    }

    @Test
    fun genericPlainSupertype_substitutesTypeArgument() {
        val module = StringConverterModule(
            default = Binding.value("d"),
            convert = Binding.factory { it.uppercase() },
            prefix = Binding.value(">"),
        )

        assertEquals("d", module.default)
        assertEquals("HELLO", module.convert("hello"))
        assertEquals(">", module.prefix)
    }

    @Test
    fun composedModule_delegatedBuilder_bindsPlainSupertypeMemberDirectly() {
        val counter = CounterModule(
            next = Binding.value(1),
            instance = Binding.value(Service(1)),
            keyed = Binding.factory { key -> Service(key.length) },
        )

        val module = TaggedCounterModule(
            counterModule = counter,
            id = Binding.value("tc1"),
            label = Binding.value("counting"),
        )

        assertEquals(1, module.next())
        assertEquals("tag:tc1", module.tag())
        assertEquals("counting", module.label)
    }

    @Test
    fun composedModule_fullBuilder_bindsEveryMemberIncludingPlainSupertypeOne() {
        val module = TaggedCounterModule(
            next = Binding.value(2),
            instance = Binding.value(Service(2)),
            keyed = Binding.factory { key -> Service(key.length) },
            id = Binding.value("tc2"),
            label = Binding.value("counting2"),
        )

        assertEquals(2, module.next())
        assertEquals("tag:tc2", module.tag())
        assertEquals("counting2", module.label)
    }
}
