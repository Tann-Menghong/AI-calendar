package com.aicalendar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    private fun map(text: String): Map<String, Any?> {
        val value = MiniJson.extractFirst(text)
        val m = MiniJson.asMap(value)
        assertNotNull("expected an object from [$text], got $value", m)
        return m!!
    }

    // ---- extraction ----------------------------------------------------------

    @Test
    fun parsesACleanObject() {
        val m = map("""{"title":"Team sync","allDay":false,"reminderMinutes":30}""")
        assertEquals("Team sync", m["title"])
        assertEquals(false, m["allDay"])
        assertEquals(30.0, m["reminderMinutes"])
    }

    @Test
    fun parsesAnObjectWrappedInProse() {
        val m = map("""Sure! Here is the event: {"title":"Coffee"} — let me know if that works.""")
        assertEquals("Coffee", m["title"])
        assertEquals(1, m.size)
    }

    @Test
    fun parsesAnObjectInsideJsonFences() {
        val m = map("```json\n{\"title\":\"Fenced\",\"reminderMinutes\":5}\n```")
        assertEquals("Fenced", m["title"])
        assertEquals(5.0, m["reminderMinutes"])
    }

    @Test
    fun parsesAnObjectInsideBareFences() {
        assertEquals("Bare", map("```\n{\"title\":\"Bare\"}\n```")["title"])
    }

    @Test
    fun toleratesTrailingCommas() {
        val m = map("""{"a":1,"b":[1,2,],}""")
        assertEquals(1.0, m["a"])
        assertEquals(listOf(1.0, 2.0), m["b"])
    }

    @Test
    fun toleratesSingleQuotesAndBareKeys() {
        val m = map("""{title:'Single quoted', 'loc':'home'}""")
        assertEquals("Single quoted", m["title"])
        assertEquals("home", m["loc"])
    }

    @Test
    fun parsesANestedEventsArray() {
        val root = map(
            """{"events":[{"title":"x","allDay":true},{"title":"y","reminderMinutes":10}]}""",
        )
        val events = MiniJson.asList(root["events"])
        assertNotNull(events)
        assertEquals(2, events!!.size)

        val first = MiniJson.asMap(events[0])
        assertEquals("x", MiniJson.string(first, "title"))
        assertEquals(true, MiniJson.boolean(first, "allDay"))

        val second = MiniJson.asMap(events[1])
        assertEquals("y", MiniJson.string(second, "title"))
        assertEquals(10, MiniJson.int(second, "reminderMinutes"))
    }

    @Test
    fun parsesATopLevelArray() {
        val list = MiniJson.asList(MiniJson.extractFirst("""[{"title":"first"},{"title":"second"}]"""))
        assertNotNull(list)
        assertEquals(2, list!!.size)
        assertEquals("first", MiniJson.string(MiniJson.asMap(list[0]), "title"))
    }

    @Test
    fun returnsNullWhenNothingParses() {
        assertNull(MiniJson.extractFirst("no json here at all"))
        assertNull(MiniJson.extractFirst(""))
        assertNull(MiniJson.extractFirst("""{"broken": """))
    }

    @Test
    fun understandsStringEscapes() {
        val m = map("""{"s":"line\nbreak \"quoted\"\tend"}""")
        assertEquals("line\nbreak \"quoted\"\tend", m["s"])
    }

    @Test
    fun understandsNullAndNegativeNumbers() {
        val m = map("""{"nul":null,"neg":-3,"frac":2.5}""")
        assertTrue(m.containsKey("nul"))
        assertNull(m["nul"])
        assertEquals(-3.0, m["neg"])
        assertEquals(2.5, m["frac"])
    }

    @Test
    fun parseReadsFromTheStartOfTheText() {
        assertEquals(mapOf("a" to 1.0), MiniJson.parse("""{"a":1}"""))
    }

    // ---- typed accessors -----------------------------------------------------

    private val accessorMap = map(
        """{"title":"a","n":5,"f":2.5,"neg":-3,"b":true,"yes":"yes","no":"no",
           "zero":0,"nul":null,"blank":"   ","nullstr":"null","numstr":" 42 "}""",
    )

    @Test
    fun stringReadsStringsNumbersAndBooleans() {
        assertEquals("a", MiniJson.string(accessorMap, "title"))
        assertEquals("5", MiniJson.string(accessorMap, "n"))
        assertEquals("2.5", MiniJson.string(accessorMap, "f"))
        assertEquals("-3", MiniJson.string(accessorMap, "neg"))
        assertEquals("true", MiniJson.string(accessorMap, "b"))
        assertEquals("42", MiniJson.string(accessorMap, "numstr"))
    }

    @Test
    fun stringSkipsMissingBlankAndNullLikeValues() {
        assertNull(MiniJson.string(accessorMap, "missing"))
        assertNull(MiniJson.string(accessorMap, "blank"))
        assertNull(MiniJson.string(accessorMap, "nul"))
        assertNull(MiniJson.string(accessorMap, "nullstr"))
        assertNull(MiniJson.string(null, "title"))
    }

    @Test
    fun stringFallsThroughToTheNextKey() {
        assertEquals("a", MiniJson.string(accessorMap, "missing", "blank", "title"))
    }

    @Test
    fun booleanReadsBooleansStringsAndNumbers() {
        assertEquals(true, MiniJson.boolean(accessorMap, "b"))
        assertEquals(true, MiniJson.boolean(accessorMap, "yes"))
        assertEquals(false, MiniJson.boolean(accessorMap, "no"))
        assertEquals(true, MiniJson.boolean(accessorMap, "n"))
        assertEquals(false, MiniJson.boolean(accessorMap, "zero"))
    }

    @Test
    fun booleanReturnsNullForUnreadableValues() {
        assertNull(MiniJson.boolean(accessorMap, "missing"))
        assertNull(MiniJson.boolean(accessorMap, "title"))
        assertNull(MiniJson.boolean(null, "b"))
    }

    @Test
    fun intReadsNumbersAndNumericStrings() {
        assertEquals(5, MiniJson.int(accessorMap, "n"))
        assertEquals(2, MiniJson.int(accessorMap, "f"))
        assertEquals(-3, MiniJson.int(accessorMap, "neg"))
        assertEquals(42, MiniJson.int(accessorMap, "numstr"))
    }

    @Test
    fun intReturnsNullForUnreadableValues() {
        assertNull(MiniJson.int(accessorMap, "missing"))
        assertNull(MiniJson.int(accessorMap, "title"))
        assertNull(MiniJson.int(accessorMap, "b"))
        assertNull(MiniJson.int(null, "n"))
    }

    @Test
    fun intFallsThroughToTheNextKey() {
        assertEquals(5, MiniJson.int(accessorMap, "missing", "n"))
    }

    @Test
    fun asMapAndAsListRejectTheWrongShape() {
        assertNull(MiniJson.asMap(MiniJson.extractFirst("[1,2,3]")))
        assertNull(MiniJson.asList(MiniJson.extractFirst("""{"a":1}""")))
        assertEquals(listOf(1.0, 2.0, 3.0), MiniJson.asList(MiniJson.extractFirst("[1,2,3]")))
        assertNull(MiniJson.asMap(null))
        assertNull(MiniJson.asList(null))
    }
}
