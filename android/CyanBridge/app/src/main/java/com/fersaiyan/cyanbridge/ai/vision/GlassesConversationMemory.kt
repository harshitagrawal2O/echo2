package com.fersaiyan.cyanbridge.ai.vision

import android.util.Log
import com.fersaiyan.cyanbridge.data.local.entity.Chat
import com.fersaiyan.cyanbridge.data.local.entity.Message
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Rolling conversation history for the glasses assistant, so a turn can refer to the ones before it.
 *
 * Every request used to be built as exactly two messages - a system prompt and the current question
 * - so the assistant had no past. Asked "what was the previous thing I asked you", it answered
 * "I'm unable to recall previous interactions", which is correct and useless: follow-ups like
 * "read it again", "what about the one on the left" or "is that the same bottle" are the normal way
 * people talk, and none of them worked.
 *
 * **Persisted to Room**, under one long-lived chat row. An in-memory-only history died with the
 * process, which on an aggressive ROM meant every app switch started a stranger. It also means the
 * wearer can read the conversation back in the Chats screen, which is the only way a blind user can
 * review what was said to them.
 *
 * **Text only, deliberately.** Past photos are not replayed. Each frame is ~60 KB of base64 and the
 * wearer takes one per turn, so carrying them would grow every request without bound and slow the
 * answer someone is waiting on. The consequence is honest and worth knowing: the assistant remembers
 * what was *said* about an earlier photo, not the photo itself, so "look at that again" cannot work
 * while "what did you say it was" can.
 *
 * Bounded twice over - by turns and by characters - because an unbounded history silently becomes a
 * bigger request every turn until it fails at the token limit, and that failure would land far from
 * its cause. The bound applies to what is *sent*; the full transcript stays in Room.
 */
object GlassesConversationMemory {

    private const val TAG = "GlassesMemory"

    /** One long-lived chat, so the thread survives restarts and is reviewable in the Chats screen. */
    private const val CHAT_ID = "glasses_assistant"
    private const val CHAT_TITLE = "Glasses assistant"

    private const val MAX_TURNS = 8
    private const val MAX_CHARS = 6_000

    private const val ROLE_USER = "User"
    private const val ROLE_ASSISTANT = "Assistant"

    private data class Turn(val question: String, val answer: String)

    private val mutex = Mutex()
    private val turns = ArrayDeque<Turn>()
    private var loaded = false

    /** Prior exchanges, oldest first, ready to splice between the system prompt and the new question. */
    suspend fun history(): List<Map<String, String>> = mutex.withLock {
        ensureLoaded()
        turns.flatMap { turn ->
            listOf(
                mapOf("role" to ROLE_USER, "content" to turn.question),
                mapOf("role" to ROLE_ASSISTANT, "content" to turn.answer),
            )
        }
    }

    suspend fun record(question: String, answer: String) = mutex.withLock {
        val q = question.trim()
        val a = answer.trim()
        if (q.isEmpty() || a.isEmpty()) return@withLock
        ensureLoaded()
        turns.addLast(Turn(q, a))
        trimSendWindow()
        persist(q, a)
    }

    /** Remembered exchanges in the window that gets sent, for logging. */
    suspend fun turnCount(): Int = mutex.withLock {
        ensureLoaded()
        turns.size
    }

    /** Starts a fresh conversation, in memory and on disk. */
    suspend fun clear() = mutex.withLock {
        turns.clear()
        loaded = true
        runCatching { MyApplication.database.messageDao().deleteMessagesByChatId(CHAT_ID) }
            .onFailure { Log.w(TAG, "Could not clear the stored conversation", it) }
        Unit
    }

    /**
     * Rebuilds the send window from Room once per process.
     *
     * Pairs stored rows back into turns rather than replaying them raw, because a half-turn - a
     * question whose answer never arrived, say after a crash mid-request - would otherwise be sent
     * as a user message with no reply and invite the model to answer it a second time.
     */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching {
            MyApplication.database.messageDao().getMessagesForChatOnce(CHAT_ID)
        }.getOrElse {
            Log.w(TAG, "Could not load the stored conversation; starting fresh", it)
            return
        }

        var pendingQuestion: String? = null
        for (row in stored) {
            when (row.role) {
                ROLE_USER -> pendingQuestion = row.content
                ROLE_ASSISTANT -> {
                    val q = pendingQuestion
                    if (q != null) {
                        turns.addLast(Turn(q, row.content))
                        pendingQuestion = null
                    }
                }
            }
        }
        trimSendWindow()
        Log.i(TAG, "Restored ${turns.size} turns from ${stored.size} stored messages")
    }

    private suspend fun persist(question: String, answer: String) {
        runCatching {
            val db = MyApplication.database
            val now = System.currentTimeMillis()
            if (db.chatDao().getChatById(CHAT_ID) == null) {
                db.chatDao().insertChat(
                    Chat(id = CHAT_ID, title = CHAT_TITLE, createdAt = now, updatedAt = now),
                )
            }
            db.messageDao().insertMessage(
                Message(UUID.randomUUID().toString(), CHAT_ID, ROLE_USER, question, now),
            )
            db.messageDao().insertMessage(
                // +1ms so the assistant reply always sorts after its question; createdAt is the
                // only ordering the DAO has, and two rows written in the same millisecond could
                // otherwise come back reversed and pair into the wrong turns on the next load.
                Message(UUID.randomUUID().toString(), CHAT_ID, ROLE_ASSISTANT, answer, now + 1),
            )
        }.onFailure { Log.w(TAG, "Could not persist the exchange", it) }
    }

    private fun trimSendWindow() {
        while (turns.size > MAX_TURNS) turns.removeFirst()
        while (turns.size > 1 && charCount() > MAX_CHARS) turns.removeFirst()
    }

    private fun charCount(): Int = turns.sumOf { it.question.length + it.answer.length }
}
