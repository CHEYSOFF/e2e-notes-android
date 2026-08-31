package my.cheysoff.core_sync_net

import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_sync_net.wire.Base64Codec
import my.cheysoff.core_sync_net.wire.JsonParseException
import my.cheysoff.core_sync_net.wire.JsonReader
import my.cheysoff.core_sync_net.wire.JsonValue
import my.cheysoff.core_sync_net.wire.JsonWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** The base64 and JSON this module hand-rolls. */
class WireCodecTest {

    // ------------------------------------------------------------------------------------------
    // base64url
    // ------------------------------------------------------------------------------------------

    /**
     * The decoder's only real specification is `core-crypto`'s encoder: every string this protocol
     * carries was produced by it (or by the server's `java.util.Base64` URL encoder, which agrees
     * with it). A decoder that disagreed by one character would turn a valid envelope into a
     * protocol error at some length and not at others -- which is exactly the shape of bug that
     * seeded random lengths find and hand-picked examples do not.
     */
    @Test
    fun `every length round-trips against core-crypto's encoder`() {
        val random = Random(20260831)
        for (length in 0..96) {
            val bytes = random.nextBytes(length)
            val encoded = Base64Url.encode(bytes)
            assertArrayEquals(
                "round trip failed at length $length",
                bytes,
                Base64Codec.decodeUrl(encoded),
            )
        }
    }

    @Test
    fun `padded base64url is accepted because the server emits and accepts both`() {
        assertArrayEquals(byteArrayOf(1), Base64Codec.decodeUrl("AQ=="))
        assertArrayEquals(byteArrayOf(1), Base64Codec.decodeUrl("AQ"))
        assertArrayEquals(byteArrayOf(1, 2), Base64Codec.decodeUrl("AQI="))
    }

    @Test
    fun `the standard alphabet is rejected so that one value has one encoding`() {
        // 0xFB 0xFF encodes as "-_8" in base64url and "+/8" in standard base64.
        assertNotNull(Base64Codec.decodeUrl("-_8"))
        assertNull(Base64Codec.decodeUrl("+/8"))
    }

    @Test
    fun `malformed base64 decodes to null rather than throwing`() {
        assertNull(Base64Codec.decodeUrl("A"))          // a lone sextet encodes no whole byte
        assertNull(Base64Codec.decodeUrl("not base64")) // space
        assertNull(Base64Codec.decodeUrl("AQ!D"))
        assertNull(Base64Codec.decodeUrl("ÿÿ"))
    }

    @Test
    fun `non-zero padding bits are rejected so two strings cannot mean one value`() {
        // "AQ" decodes 0x01 with four zero padding bits. "AR" sets one of them; both would
        // otherwise decode to the same single byte.
        assertArrayEquals(byteArrayOf(1), Base64Codec.decodeUrl("AQ"))
        assertNull(Base64Codec.decodeUrl("AR"))
    }

    /**
     * OkHttp's `CertificatePinner` wants **standard** base64, not the base64url the rest of this
     * protocol uses. The wrong alphabet does not fail loudly: the builder accepts it and the pin
     * simply never matches, which looks like a broken server rather than a broken client.
     */
    @Test
    fun `an spki pin is rendered as padded standard base64`() {
        val pin = ByteArray(32) { 0xFB.toByte() }
        val rendered = my.cheysoff.core_sync_net.http.OkHttpTransport.okHttpPin(pin)
        assertTrue(rendered.startsWith("sha256/"))
        val body = rendered.removePrefix("sha256/")
        assertTrue("standard base64 uses + and /", body.contains('+') || body.contains('/'))
        assertEquals("a 32-byte digest is 44 padded base64 characters", 44, body.length)
    }

    // ------------------------------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------------------------------

    @Test
    fun `numbers are kept exactly and do not lose precision through a double`() {
        val big = 9007199254740993L // 2^53 + 1: not representable as a Double
        val parsed = JsonReader.parse("""{"seq":$big}""") as JsonValue.Obj
        assertEquals(big, (parsed.fields["seq"] as JsonValue.Num).raw.toLong())
    }

    @Test
    fun `escapes are decoded`() {
        // The JSON document, written out literally, is:  "a\"b\\c\ndA"
        val document = "\"a\\\"b\\\\c\\nd\\u0041\""
        val parsed = JsonReader.parse(document) as JsonValue.Str
        assertEquals("a\"b\\c\ndA", parsed.value)
    }

    @Test
    fun `a duplicate key is refused rather than silently resolved`() {
        assertParseFails("""{"a":1,"a":2}""")
    }

    @Test
    fun `an unescaped control character inside a string is refused`() {
        assertParseFails("\"a" + 1.toChar() + "b\"")
    }

    @Test
    fun `trailing content after the document is refused`() {
        assertParseFails("""{"a":1} {"b":2}""")
    }

    /**
     * Deep nesting is a denial-of-service shape, not a curiosity: a few kilobytes of `[` is a
     * `StackOverflowError` in a naive recursive parser, and a `StackOverflowError` is an `Error`,
     * so it escapes every `catch (e: Exception)` in this module.
     */
    @Test
    fun `absurdly deep nesting is refused instead of overflowing the stack`() {
        assertParseFails("[".repeat(10_000) + "]".repeat(10_000))
    }

    @Test
    fun `common malformed bodies are refused`() {
        assertParseFails("")
        assertParseFails("{")
        assertParseFails("""{"a":}""")
        assertParseFails("""{"a" 1}""")
        assertParseFails("""{a:1}""")
        assertParseFails("<html>502</html>")
        assertParseFails("""{"a":01x}""")
    }

    @Test
    fun `the writer escapes what a device label can actually contain`() {
        val written = JsonWriter().obj {
            field("deviceLabel", "Vova\"s \\ tablet\nline")
        }.toString()

        val parsed = JsonReader.parse(written) as JsonValue.Obj
        assertEquals(
            "Vova\"s \\ tablet\nline",
            (parsed.fields["deviceLabel"] as JsonValue.Str).value,
        )
    }

    @Test
    fun `the writer emits arrays of objects in order`() {
        val written = JsonWriter().obj {
            arrayField("items", listOf("a", "b")) { id -> field("blindedId", id) }
        }.toString()

        assertEquals("""{"items":[{"blindedId":"a"},{"blindedId":"b"}]}""", written)
    }

    private fun assertParseFails(text: String) {
        try {
            JsonReader.parse(text)
            fail("expected a parse failure for: ${text.take(40)}")
        } catch (_: JsonParseException) {
            // expected
        }
    }
}
