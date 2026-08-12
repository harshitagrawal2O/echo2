package com.fersaiyan.cyanbridge.plugins.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roll call is where a wrong name enters the system, so the false-positive cases matter more than
 * the happy path: a name Cue never binds costs one "someone new", and a name Cue binds wrongly gets
 * said out loud to a room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CueRollCallTest {

    private fun turn(label: String, text: String, endMs: Long = 1_000) =
        Turn(speakerLabel = label, text = text, startMs = endMs - 1_000, endMs = endMs)

    @Test
    fun `binds a name from an ordinary self introduction`() {
        val bindings = CueRollCall.detectLocally(
            listOf(turn(ALICE, "Hi, I'm Sarah. Good to meet you.")),
        )

        assertEquals(1, bindings.size)
        assertEquals("Sarah", bindings.single().name)
        assertEquals(NameConfidence.HIGH, bindings.single().confidence)
    }

    @Test
    fun `binds from my name is`() {
        val bindings = CueRollCall.detectLocally(listOf(turn(ALICE, "My name is Priya Raman.")))

        assertEquals("Priya Raman", bindings.single().name)
    }

    @Test
    fun `binds from name-here`() {
        val bindings = CueRollCall.detectLocally(listOf(turn(ALICE, "Grant here, joining late.")))

        assertEquals("Grant", bindings.single().name)
    }

    @Test
    fun `does not mistake ordinary speech for an introduction`() {
        val notIntroductions = listOf(
            "I'm going to grab a coffee.",
            "I'm not sure about that.",
            "I'm really sorry, I missed that.",
            "I am here already.",
            "I'm just thinking about the numbers.",
            "I'm working on the deck now.",
        )

        for (text in notIntroductions) {
            assertNull("\"$text\" should not look like an introduction", CueRollCall.detectInText(text))
        }
    }

    @Test
    fun `an uncased transcript yields a name Cue will not say aloud`() {
        val detected = CueRollCall.detectInText("hi i'm sarah")

        assertEquals("Sarah", detected?.first)
        // Medium confidence never reaches the user's ear as a name — it degrades to "someone new".
        assertEquals(NameConfidence.MEDIUM, detected?.second)
    }

    @Test
    fun `the wearer is never bound by roll call`() {
        val bindings = CueRollCall.detectLocally(
            listOf(turn(WEARER, "Hi everyone, I'm Alex.")),
            wearerLabel = WEARER,
        )

        assertTrue(bindings.isEmpty())
    }

    @Test
    fun `three people enrol from natural conversation with no commands`() {
        val bindings = CueRollCall.detectLocally(
            listOf(
                turn(ALICE, "Hi, I'm Sarah.", 2_000),
                turn(BOB, "I'm Priya, nice to meet you.", 5_000),
                turn(CAROL, "My name is Grant.", 9_000),
                turn(ALICE, "So what are we covering today?", 12_000),
            ),
        )

        assertEquals(
            setOf("Sarah", "Priya", "Grant"),
            bindings.map { it.name }.toSet(),
        )
        assertTrue(bindings.all { it.confidence == NameConfidence.HIGH })
    }

    @Test
    fun `the model reply is parsed and low confidence entries are discarded`() {
        val bindings = CueRollCall.parseResponse(
            """
            {"speakers":[
              {"label":"speaker_1","name":"Sarah","confidence":"high"},
              {"label":"speaker_2","name":"Priya","confidence":"medium"},
              {"label":"speaker_3","name":"Maybe Bob","confidence":"low"}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, bindings.size)
        assertEquals(NameConfidence.HIGH, bindings.first().confidence)
        assertTrue(bindings.none { it.name == "Maybe Bob" })
    }

    @Test
    fun `a malformed model reply binds nothing rather than something wrong`() {
        assertTrue(CueRollCall.parseResponse("sorry, I can't help with that").isEmpty())
        assertTrue(CueRollCall.parseResponse("").isEmpty())
        assertTrue(CueRollCall.parseResponse("{\"speakers\": \"nope\"}").isEmpty())
    }

    @Test
    fun `the prompt carries the wearer exclusion`() {
        val prompt = CueRollCall.buildPrompt(
            listOf(turn(ALICE, "Hi, I'm Sarah.")),
            wearerLabel = WEARER,
        )

        assertTrue(prompt.contains(WEARER))
        assertTrue(prompt.contains("never emit a binding"))
        assertTrue(prompt.contains("Hi, I'm Sarah."))
    }

    private companion object {
        const val WEARER = "speaker_0"
        const val ALICE = "speaker_1"
        const val BOB = "speaker_2"
        const val CAROL = "speaker_3"
    }
}
