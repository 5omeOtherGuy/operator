package dev.operator.core.grammar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11: the JSON-schema → GBNF generator must drop the keywords llama.cpp cannot express (the named
 * one is `uniqueItems`) and leave them to the executor's re-validation, while still accepting valid
 * instances and rejecting instances that violate what it *does* express.
 */
class JsonSchemaToGbnfTest {

    private fun schema(): JsonValue.Obj = parsed(
        """
        {
          "type": "object",
          "additionalProperties": false,
          "uniqueItems": true,
          "format": "uri",
          "pattern": "^.{1,}$",
          "required": ["city"],
          "properties": {
            "city": { "type": "string", "minLength": 1, "maxLength": 20 },
            "unit": { "type": "string", "enum": ["c", "f"] },
            "count": { "type": "integer" },
            "tags": { "type": "array", "items": { "type": "string", "maxLength": 10 }, "uniqueItems": true }
          }
        }
        """.trimIndent(),
    )

    private fun parsed(text: String): JsonValue.Obj = ((Json.parse(text) as JsonParse.Ok).value as JsonValue.Obj)

    @Test
    fun `drops the keywords C11 names`() {
        val conversion = JsonSchemaToGbnf().convert(schema(), "args")
        assertTrue("uniqueItems must be dropped (C11)", "uniqueItems" in conversion.droppedKeywords)
        assertTrue("format must be dropped", "format" in conversion.droppedKeywords)
        assertTrue("pattern must be dropped", "pattern" in conversion.droppedKeywords)
        assertTrue("required is left to the executor's re-validation", "required" in conversion.droppedKeywords)
        assertFalse("type is expressed", "type" in conversion.droppedKeywords)
        assertFalse("enum is expressed", "enum" in conversion.droppedKeywords)
        assertFalse("maxLength is expressed", "maxLength" in conversion.droppedKeywords)
    }

    @Test
    fun `accepts valid instances and rejects what it expresses`() {
        val g = GbnfMatcher.parse(JsonSchemaToGbnf().convert(schema(), "args").gbnf.render())
        assertTrue(g.accepts("""{"city":"Berlin"}"""))
        assertTrue(g.accepts("""{"city":"Berlin","unit":"c","count":3}"""))
        assertTrue(g.accepts("""{"city":"Berlin","tags":["a","b"]}"""))
        assertTrue(g.accepts("""{}"""))

        // An undeclared property is outside additionalProperties:false.
        assertFalse(g.accepts("""{"city":"Berlin","extra":1}"""))
        // The value type is expressed.
        assertFalse(g.accepts("""{"city":5}"""))
        // The enum is expressed.
        assertFalse(g.accepts("""{"city":"Berlin","unit":"x"}"""))
        // tags must be an array of strings, not a string.
        assertFalse(g.accepts("""{"city":"Berlin","tags":"nope"}"""))
    }
}
