package me.river.remoter.core.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Reads the fixtures remoter-proto writes and rebuilds everything from them.
 * Any drift between the Rust and Kotlin sides fails here first.
 */
class ContractTest {
    private fun fixture(name: String): JsonElement {
        val text = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
        return Json.parseToJsonElement(text)
    }

    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content

    @Test
    fun signing_urls_and_canonical_strings_match_byte_for_byte() {
        val root = fixture("signing.json").jsonObject
        val spki = B64.decode(root.str("test_only_public_key_spki"))!!
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.size >= 8)
        val mismatches = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val url = HttpUrl.Builder().scheme("https").host(LAPTOP_ADDR).port(8443).addPathSegment("v1").apply {
                o["segments"]!!.jsonArray.forEach { addPathSegment(it.jsonPrimitive.content) }
                o["query"]!!.jsonArray.forEach { q -> addQueryParameter(q.jsonArray[0].jsonPrimitive.content, q.jsonArray[1].jsonPrimitive.content) }
            }.build()
            val target = Canonical.target(url)
            if (target != o.str("target")) mismatches += "${o.str("name")}: okhttp=$target fixture=${o.str("target")}"
            // The canonical string uses the fixture's wire target, which is what the laptop sees.
            val wire = HttpUrl.Builder().scheme("https").host(LAPTOP_ADDR).port(8443).build()
                .newBuilder(o.str("target"))!!.build()
            val body = o.str("body").toByteArray()
            assertEquals(o.str("name"), o.str("body_sha256"), Canonical.bodyHashHex(body))
            val canon = Canonical.build(
                o.str("method"), wire, o.str("device"), o["timestamp_ms"]!!.jsonPrimitive.long, o.str("nonce"), body,
            )
            assertEquals(o.str("name"), o.str("canonical"), canon)
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(canon.toByteArray())
                verify(B64.decode(o.str("signature_der"))!!)
            }
            assertTrue("signature ${o.str("name")}", ok)
        }
        assertEquals("OkHttp encodes differently from the fixture", emptyList<String>(), mismatches)
    }

    @Test
    fun name_rules_match() {
        val root = fixture("names.json").jsonObject
        root["folder"]!!.jsonArray.forEach {
            assertEquals(it.str("name"), it.jsonObject["valid"]!!.jsonPrimitive.boolean, Names.isValidFolderName(it.str("name")))
        }
        root["session"]!!.jsonArray.forEach {
            assertEquals(it.str("name"), it.jsonObject["valid"]!!.jsonPrimitive.boolean, Names.isValidSessionName(it.str("name")))
        }
        root["session_from_folder"]!!.jsonArray.forEach {
            assertEquals(it.str("folder"), it.str("name"), Names.sessionNameFromFolder(it.str("folder")))
        }
        root["unsupported"]!!.jsonArray.forEach {
            val raw = it.str("hex").chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()
            assertEquals(it.str("hex"), it.jsonObject["unsupported"]!!.jsonPrimitive.boolean, Names.isUnsupportedName(raw))
        }
    }

    @Test
    fun pairing_transcript_mac_code_and_link_match() {
        val o = fixture("pair.json").jsonObject
        val secret = B64.decode(o.str("secret"))!!
        val t = Pairing.transcript(
            B64.decode(o.str("server_fp"))!!, B64.decode(o.str("tls_spki"))!!, B64.decode(o.str("sig_spki"))!!,
            o.str("device_name"),
        )
        assertEquals(o.str("transcript_hex"), B64.hex(t))
        assertEquals(o.str("mac"), B64.encode(Pairing.mac(secret, t)))
        assertEquals(o.str("code"), Pairing.confirmationCode(secret, t))
        val link = Pairing.Link.parse(o.str("link"))
        assertNotNull(link)
        assertEquals(o.str("link"), link!!.toUri())
        assertNull(Pairing.Link.parse(o.str("link") + "&x=1"))
        assertNull(Pairing.Link.parse(o.str("link").replace("p=8443", "p=08443")))
        assertNull(Pairing.Link.parse(o.str("link").replace("h=10.66.66.3", "h=010.66.66.3")))
        assertTrue("toString must not leak the secret", !link.toString().contains(B64.encode(secret)))
    }

    private fun dropNulls(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it != JsonNull }.mapValues { dropNulls(it.value) })
        is JsonArray -> JsonArray(e.map(::dropNulls))
        else -> e
    }

    private inline fun <reified T> roundTrip(e: JsonElement) = roundTrip(serializer<T>(), e)

    private fun <T> roundTrip(s: KSerializer<T>, e: JsonElement) {
        val value = RemoterJson.decodeFromJsonElement(s, e)
        assertEquals(dropNulls(e), dropNulls(RemoterJson.encodeToJsonElement(s, value)))
    }

    @Test
    fun every_response_round_trips() {
        val o = fixture("responses.json").jsonObject
        roundTrip<Health>(o["health"]!!)
        roundTrip<ListResponse>(o["list"]!!)
        roundTrip<SearchResponse>(o["search"]!!)
        roundTrip<RecentResponse>(o["recent"]!!)
        roundTrip<MkdirRequest>(o["mkdir_request"]!!)
        roundTrip<MkdirResponse>(o["mkdir"]!!)
        roundTrip<SpawnRequest>(o["spawn_request"]!!)
        roundTrip<SpawnRequest>(o["resume_request"]!!)
        roundTrip<SpawnRequest>(o["handoff_request"]!!)
        roundTrip<HistoryResponse>(o["history"]!!)
        roundTrip<SpawnResponse>(o["spawn"]!!)
        roundTrip<SessionsResponse>(o["sessions"]!!)
        roundTrip<LiveSessions>(o["live_sessions"]!!)
        roundTrip<SessionDetail>(o["session_detail"]!!)
        roundTrip<ViewTokenResponse>(o["view_token"]!!)
        roundTrip<AttestChallenge>(o["attest_challenge"]!!)
        roundTrip<AttestResponse>(o["attest_response"]!!)
        roundTrip<LockResponse>(o["lock"]!!)
        roundTrip<AuditPage>(o["audit"]!!)
        roundTrip<PairResponse>(o["pair"]!!)
        val known = setOf(
            "health", "list", "search", "recent", "mkdir_request", "mkdir", "spawn_request", "spawn", "sessions",
            "session_detail", "view_token", "attest_challenge", "attest_response", "lock", "audit", "pair", "events",
            "live_sessions", "resume_request", "handoff_request", "history",
        )
        assertEquals("a fixture has no Kotlin model", known, o.keys)
        o["events"]!!.jsonArray.forEach {
            val ev = Event.parse(it.str("event"), it.jsonObject["data"].toString())
            assertNotNull(it.str("event"), ev)
        }
        val live = LiveEvent.parse("sessions", o["live_sessions"].toString()) as LiveEvent.Sessions
        assertEquals(RemoterJson.decodeFromJsonElement(LiveSessions.serializer(), o["live_sessions"]!!).sessions, live.sessions)
        val health = LiveEvent.parse("health", o["health"].toString()) as LiveEvent.Health
        assertEquals(RemoterJson.decodeFromJsonElement(Health.serializer(), o["health"]!!), health.health)
        assertNull("a newer laptop's event is skipped, not fatal", LiveEvent.parse("weather", "{}"))
    }

    @Test
    fun every_error_body_round_trips() {
        val codes = fixture("errors.json").jsonArray.map {
            roundTrip<ErrorBody>(it.jsonObject["body"]!!)
            assertTrue(it.jsonObject["status"]!!.jsonPrimitive.int in 400..599)
            RemoterJson.decodeFromJsonElement(ErrorBody.serializer(), it.jsonObject["body"]!!).code
        }
        assertEquals("every Kotlin code has a fixture", ErrorCode.entries.toSet(), codes.toSet())
    }

    @Test
    fun resume_body_matches_fixture() {
        val o = fixture("responses.json").jsonObject
        val resume = RemoterJson.decodeFromJsonElement(SpawnRequest.serializer(), o["resume_request"]!!)
        assertEquals("c82d8b5c-edd4-453e-8d59-4748ff325c03", resume.resume)
        // Key order differs (serde sorts the fixture), and the laptop doesn't care, so the JSON is compared, not the bytes.
        assertEquals(o["resume_request"], Json.parseToJsonElement(RemoterJson.encodeToString(SpawnRequest.serializer(), resume)))
        val plain = RemoterJson.encodeToString(SpawnRequest.serializer(), SpawnRequest("Projects/remoter", "remoter", SpawnMode.SameDir))
        assertEquals(o["spawn_request"], Json.parseToJsonElement(plain))
        assertTrue(plain, !plain.contains("resume"))
        assertTrue(plain, !plain.contains("handoff"))
    }

    @Test
    fun handoff_body_matches_fixture() {
        val o = fixture("responses.json").jsonObject
        val handoff = RemoterJson.decodeFromJsonElement(SpawnRequest.serializer(), o["handoff_request"]!!)
        assertEquals("c82d8b5c-edd4-453e-8d59-4748ff325c03", handoff.handoff)
        assertNull(handoff.resume)
        assertEquals(o["handoff_request"], Json.parseToJsonElement(RemoterJson.encodeToString(SpawnRequest.serializer(), handoff)))
        val events = o["events"]!!.jsonArray.mapNotNull { Event.parse(it.str("event"), it.jsonObject["data"].toString()) }
        assertTrue(events.any { it is Event.PhaseEvent && it.step == Phase.Handoff })
        assertTrue(events.any { it is Event.StateEvent && it.reason == StuckReason.HandoffFailed })
    }

    @Test
    fun history_lives_under_fs_with_the_folder_as_query() {
        assertEquals("/v1/fs/history?path=Projects%2Fmy%20app", Canonical.target(Paths.history("Projects/my app")))
    }

    @Test
    fun unknown_fields_are_refused() {
        val bad = """{"path":"p","name":"n","mode":"same-dir","extra":1}"""
        assertTrue(runCatching { RemoterJson.decodeFromString<SpawnRequest>(bad) }.isFailure)
    }
}
