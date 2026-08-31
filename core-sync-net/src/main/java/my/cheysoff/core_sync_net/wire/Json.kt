package my.cheysoff.core_sync_net.wire

/**
 * A minimal JSON reader and writer, sized for exactly the bodies `server/` exchanges.
 *
 * ## Why this is hand-rolled
 *
 * The three alternatives were each rejected for a concrete reason, not on taste:
 *
 *  - **`org.json`** is an Android framework class. In a `src/test` JVM run it is a stub whose
 *    methods throw, so building the wire layer on it would mean the wire layer could only be tested
 *    under Robolectric or on a device -- and this module exists to be testable without either.
 *    `core-crypto`'s [my.cheysoff.core_crypto.sync.Base64Url] was hand-rolled for the same reason
 *    and says so.
 *  - **`kotlinx.serialization`** would mean applying the Kotlin serialization compiler plugin. Every
 *    module in this project compiles through AGP's *built-in* Kotlin support and none of them apply
 *    the standalone Kotlin Gradle plugin, so adding a KGP compiler plugin is a build change of
 *    unknown blast radius for a handful of flat objects.
 *  - **`java.util.Base64`-style platform JSON** does not exist; there is no JSON in the Java SE 11
 *    standard library.
 *
 * ## What this is not
 *
 * It is not a general JSON library. It parses the subset RFC 8259 defines and nothing beyond it,
 * it has a hard nesting limit, and it makes no attempt to be fast. It is used against exactly one
 * server, whose bodies are machine-generated, and the real proof that reader and writer agree with
 * that server is `SyncServerContractTest` -- a test that runs the actual server rather than a
 * hand-written fake that could share this file's misunderstandings.
 *
 * ## Numbers
 *
 * Numbers are kept as their raw text and converted on demand. Every number this protocol carries is
 * an integer (`seq`, `baseSeq`, `ts`, epoch milliseconds), and routing them through `Double` -- as
 * a naive parser does -- silently loses precision above 2^53. That is not reachable with today's
 * values, and it is one line to prevent rather than a comment explaining why it is fine.
 */
internal sealed class JsonValue {

    internal object Null : JsonValue()

    internal class Bool(val value: Boolean) : JsonValue()

    /** The number exactly as it appeared. See the class KDoc for why it is not parsed eagerly. */
    internal class Num(val raw: String) : JsonValue()

    internal class Str(val value: String) : JsonValue()

    internal class Arr(val items: List<JsonValue>) : JsonValue()

    internal class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

/** Thrown by [JsonReader] when the input is not JSON. Never carries any of the input. */
internal class JsonParseException(message: String) : Exception(message)

/**
 * A recursive-descent JSON parser.
 *
 * The depth limit is a denial-of-service guard, not a style choice: a body consisting of ten
 * thousand `[` characters is a few kilobytes on the wire and a `StackOverflowError` in a naive
 * parser, and a `StackOverflowError` is an `Error` rather than an `Exception`, so it escapes every
 * `catch (e: Exception)` this module has.
 */
internal class JsonReader private constructor(private val input: String) {

    private var index = 0
    private var depth = 0

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (index >= input.length) fail("unexpected end of input")
        return when (val c = input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> parseLiteral("true", JsonValue.Bool(true))
            'f' -> parseLiteral("false", JsonValue.Bool(false))
            'n' -> parseLiteral("null", JsonValue.Null)
            else ->
                if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected character")
        }
    }

    private fun parseObject(): JsonValue {
        enter()
        index++ // '{'
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            leave()
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("object key must be a string")
            val key = parseString()
            skipWhitespace()
            if (peek() != ':') fail("expected ':' after object key")
            index++
            // A duplicate key is refused rather than resolved. Parsers that keep the last
            // occurrence and parsers that keep the first both exist, so a body with a repeated
            // field means two readers can legitimately disagree about what it says -- which is
            // precisely the class of bug this whole module is written to avoid.
            if (fields.put(key, parseValue()) != null) fail("duplicate object key")
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    leave()
                    return JsonValue.Obj(fields)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonValue {
        enter()
        index++ // '['
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            leave()
            return JsonValue.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    leave()
                    return JsonValue.Arr(items)
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        index++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (index >= input.length) fail("unterminated string")
            when (val c = input[index]) {
                '"' -> {
                    index++
                    return out.toString()
                }
                '\\' -> {
                    index++
                    if (index >= input.length) fail("unterminated escape")
                    when (val esc = input[index]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 >= input.length) fail("truncated \\u escape")
                            val hex = input.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> fail("unknown escape '$esc'")
                    }
                    index++
                }
                else -> {
                    // RFC 8259: the control characters U+0000..U+001F must be escaped inside a
                    // string. Accepting them raw would mean this reader accepts documents the
                    // server's own strict decoder rejects, and the two halves must agree on what
                    // valid means.
                    if (c < ' ') fail("unescaped control character in string")
                    out.append(c)
                    index++
                }
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = index
        if (peek() == '-') index++
        while (index < input.length && input[index] in '0'..'9') index++
        if (index < input.length && input[index] == '.') {
            index++
            while (index < input.length && input[index] in '0'..'9') index++
        }
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            while (index < input.length && input[index] in '0'..'9') index++
        }
        val raw = input.substring(start, index)
        if (raw.isEmpty() || raw == "-") fail("malformed number")
        return JsonValue.Num(raw)
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!input.startsWith(literal, index)) fail("unknown literal")
        index += literal.length
        return value
    }

    private fun peek(): Char {
        if (index >= input.length) fail("unexpected end of input")
        return input[index]
    }

    private fun skipWhitespace() {
        while (index < input.length && (input[index] == ' ' || input[index] == '\t' ||
                    input[index] == '\n' || input[index] == '\r')
        ) {
            index++
        }
    }

    private fun enter() {
        depth++
        if (depth > MAX_DEPTH) fail("nesting is too deep")
    }

    private fun leave() {
        depth--
    }

    private fun fail(reason: String): Nothing = throw JsonParseException(reason)

    internal companion object {

        /**
         * The deepest this server's bodies actually nest is 4 (`{ results: [ { current: { … } } ] }`).
         * 32 is far past anything legitimate and far short of the stack.
         */
        private const val MAX_DEPTH = 32

        /**
         * Parses [text] as a complete JSON document.
         *
         * Trailing content after the top-level value is an error rather than ignored: two JSON
         * documents concatenated are not one document, and treating the first as the answer would
         * hide a framing bug for as long as the first half happened to be well-formed.
         */
        fun parse(text: String): JsonValue {
            val reader = JsonReader(text)
            val value = reader.parseValue()
            reader.skipWhitespace()
            if (reader.index != text.length) throw JsonParseException("trailing content")
            return value
        }
    }
}

/**
 * A JSON writer for flat request bodies.
 *
 * There is no object model on the writing side because there does not need to be one: every request
 * this client sends is a flat object, or an object whose single array holds flat objects. The
 * builder shape keeps the produced bytes obvious at the call site.
 */
internal class JsonWriter {

    private val out = StringBuilder()

    /** Writes `{ … }` around whatever [body] emits. */
    fun obj(body: ObjectScope.() -> Unit): JsonWriter {
        out.append('{')
        ObjectScope().body()
        out.append('}')
        return this
    }

    override fun toString(): String = out.toString()

    fun toBytes(): ByteArray = out.toString().toByteArray(Charsets.UTF_8)

    internal inner class ObjectScope {
        private var first = true

        private fun separate() {
            if (!first) out.append(',')
            first = false
        }

        fun field(name: String, value: String) {
            separate()
            writeString(name)
            out.append(':')
            writeString(value)
        }

        fun field(name: String, value: Long) {
            separate()
            writeString(name)
            out.append(':')
            out.append(value.toString())
        }

        /** Writes `"name": [ … ]`, calling [body] once per element of [items]. */
        fun <T> arrayField(name: String, items: List<T>, body: ObjectScope.(T) -> Unit) {
            separate()
            writeString(name)
            out.append(":[")
            items.forEachIndexed { position, item ->
                if (position > 0) out.append(',')
                out.append('{')
                ObjectScope().body(item)
                out.append('}')
            }
            out.append(']')
        }
    }

    private fun writeString(value: String) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // Everything below U+0020 must be escaped, and \u is the only escape that covers
                // all of it. Device labels are user-typed and reach the server through this
                // writer, so this branch is reachable in production, not theoretical.
                c < ' ' -> out.append("\\u").append(HEX_DIGITS[c.code shr 12 and 0xF])
                    .append(HEX_DIGITS[c.code shr 8 and 0xF])
                    .append(HEX_DIGITS[c.code shr 4 and 0xF])
                    .append(HEX_DIGITS[c.code and 0xF])
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
