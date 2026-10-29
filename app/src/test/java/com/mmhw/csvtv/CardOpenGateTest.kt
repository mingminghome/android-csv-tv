package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardOpenGateTest {

    @Test
    fun secondClickOnSameUrlIsIgnored() {
        val gate = CardOpenGate()
        val first = gate.tryBegin("https://example.com/a")
        assertTrue(first is CardOpenGate.Begin.Started)
        assertEquals(1, (first as CardOpenGate.Begin.Started).generation)
        assertNull(first.previousUrl)

        val second = gate.tryBegin("https://example.com/a")
        assertTrue(second is CardOpenGate.Begin.AlreadyOpening)
        assertEquals(1, gate.generation)
    }

    @Test
    fun sameUrlIgnoresTrimAndCase() {
        val gate = CardOpenGate()
        gate.tryBegin("https://Example.com/A")
        assertTrue(gate.tryBegin("  https://example.com/a  ") is CardOpenGate.Begin.AlreadyOpening)
    }

    @Test
    fun differentUrlReplacesInFlightOpen() {
        val gate = CardOpenGate()
        gate.tryBegin("https://example.com/a")
        val next = gate.tryBegin("https://example.com/b") as CardOpenGate.Begin.Started
        assertEquals("https://example.com/a", next.previousUrl)
        assertEquals(2, next.generation)
        assertTrue(gate.isCurrent("https://example.com/b", 2))
        assertFalse(gate.isCurrent("https://example.com/a", 1))
    }

    @Test
    fun staleGenerationIsNotCurrent() {
        val gate = CardOpenGate()
        gate.tryBegin("https://example.com/a")
        gate.tryBegin("https://example.com/b")
        assertFalse(gate.isCurrent("https://example.com/a", 1))
        assertTrue(gate.isCurrent("https://example.com/b", 2))
    }

    @Test
    fun clearAllowsSameUrlAgain() {
        val gate = CardOpenGate()
        gate.tryBegin("https://example.com/a")
        gate.clear()
        assertNull(gate.openingUrl)
        val again = gate.tryBegin("https://example.com/a")
        assertTrue(again is CardOpenGate.Begin.Started)
        assertEquals(2, (again as CardOpenGate.Begin.Started).generation)
    }
}
