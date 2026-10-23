package my.cheysoff.core_blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import my.cheysoff.core_domain.sync.Hlc

/**
 * The v1 JSON form of a [BlockDocument]:
 * `{"v":1,"blocks":[{"id","t","text","spans":[[start,end,"b"]],"done","ref","pos","c","pc"}],"gone":[{"id","c","pos"?,"pc"?}]}`.
 *
 * Encoding is canonical -- keys in schema order, spans sorted by (start, end, style), tombstones by
 * id, blocks by (pos, id); span ties by InlineStyle declaration order -- because the merge breaks clock ties by comparing encoded JSON, and
 * two devices must compute the same string for the same value or they never converge.
 *
 * Decoding is strict about the things that would make a misread dangerous (version, block type,
 * clocks) and returns null rather than guessing: a body this build cannot fully understand must be
 * left alone, not "repaired" and saved back over a newer build's data.
 */
object BlockCodec {

    private const val VERSION = 1

    fun encode(doc: BlockDocument): String = buildJsonObject {
        put("v", VERSION)
        put("blocks", buildJsonArray {
            for (b in doc.blocks) add(encodeBlock(b))
        })
        put("gone", buildJsonArray {
            for (t in doc.gone.sortedBy { it.id }) {
                add(buildJsonObject {
                    put("id", t.id)
                    put("c", t.clock.toString())
                    // Omitted when unknown, so a plain tombstone encodes exactly as it did before
                    // tombstones carried a position. Both must be blank: a `pos` under a ZERO clock
                    // (an unstamped block that died in a merge) would otherwise not round-trip.
                    if (t.posClock != Hlc.ZERO || t.pos.isNotEmpty()) {
                        put("pos", t.pos)
                        put("pc", t.posClock.toString())
                    }
                })
            }
        })
    }.toString()

    private fun encodeBlock(b: Block): JsonObject = buildJsonObject {
        put("id", b.id)
        put("t", b.type.wire)
        put("text", b.text)
        put("spans", buildJsonArray {
            val sorted = b.spans.sortedWith(
                compareBy<StyleSpan> { it.start }.thenBy { it.end }.thenBy { it.style.ordinal },
            )
            for (s in sorted) {
                add(buildJsonArray {
                    add(JsonPrimitive(s.start))
                    add(JsonPrimitive(s.end))
                    add(JsonPrimitive(s.style.wire))
                })
            }
        })
        put("done", b.done)
        // Omitted rather than null: most blocks have no ref and a null key is noise in every body.
        if (b.ref != null) put("ref", b.ref)
        put("pos", b.pos)
        put("c", b.clock.toString())
        put("pc", b.posClock.toString())
    }

    /** The document in [json], or null if it is malformed, of another version, or holds anything unknown. */
    fun decode(json: String): BlockDocument? = try {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) {
            null
        } else {
            val blocks = (root["blocks"]?.jsonArray ?: JsonArray(emptyList()))
                .map { decodeBlock(it.jsonObject) ?: return null }
            val gone = (root["gone"]?.jsonArray ?: JsonArray(emptyList())).map {
                val o = it.jsonObject
                Tombstone(
                    id = o.getValue("id").jsonPrimitive.content,
                    clock = Hlc.parse(o.getValue("c").jsonPrimitive.content) ?: return null,
                    pos = o["pos"]?.jsonPrimitive?.content ?: "",
                    posClock = clockOf(o["pc"]) ?: return null,
                )
            }
            BlockDocument(blocks, gone)
        }
    } catch (e: Exception) {
        // Covers malformed JSON, wrong element shapes and missing required keys alike: all mean
        // "not a document this build can read".
        null
    }

    private fun decodeBlock(o: JsonObject): Block? {
        val type = BlockType.entries.firstOrNull { it.wire == o["t"]?.jsonPrimitive?.contentOrNull }
            ?: return null
        val spans = (o["spans"]?.jsonArray ?: JsonArray(emptyList())).map {
            val a = it.jsonArray
            val style = InlineStyle.entries.firstOrNull { s -> s.wire == a[2].jsonPrimitive.content }
                ?: return null
            StyleSpan(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, style)
        }
        return Block(
            id = o.getValue("id").jsonPrimitive.content,
            type = type,
            text = o["text"]?.jsonPrimitive?.content ?: "",
            spans = spans,
            done = o["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            ref = o["ref"]?.jsonPrimitive?.contentOrNull,
            pos = o["pos"]?.jsonPrimitive?.content ?: "",
            clock = clockOf(o["c"]) ?: return null,
            posClock = clockOf(o["pc"]) ?: return null,
        )
    }

    /** An absent clock is [Hlc.ZERO]; a present but unparseable one is an error. */
    private fun clockOf(e: JsonElement?): Hlc? =
        if (e == null) Hlc.ZERO else Hlc.parse(e.jsonPrimitive.content)
}
