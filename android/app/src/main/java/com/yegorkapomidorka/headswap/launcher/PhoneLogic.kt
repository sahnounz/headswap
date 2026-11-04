package com.yegorkapomidorka.headswap.launcher

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class CallType {
    INCOMING, OUTGOING, MISSED, OTHER;

    companion object {
        /** CallLog.Calls.TYPE: 1 incoming, 2 outgoing, 3 omitted, 5 rejected (for head unit - incoming). */
        fun fromLog(type: Int): CallType = when (type) {
            1, 5 -> INCOMING
            2 -> OUTGOING
            3 -> MISSED
            else -> OTHER
        }
    }
}

data class CallRecord(val number: String, val name: String?, val type: CallType, val timeMs: Long) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: number
}

data class ContactRecord(val id: Long, val name: String, val number: String)

/** State of call by TelephonyManager: CALL_STATE_IDLE/RINGING/OFFHOOK. */
object CallState { const val IDLE = 0; const val RINGING = 1; const val OFFHOOK = 2 }

/** What does the "Phone" card (PhoneStates.dc.html, A-Zh status) show? */
sealed interface PhoneCardModel {
    /** A: There are missing ones. */
    data class Missed(val count: Int, val last: CallRecord) : PhoneCardModel
    /** B: There are no missed ones, there is a final challenge. */
    data class LastCall(val last: CallRecord) : PhoneCardModel
    /** Q: No calls. */
    data object Empty : PhoneCardModel
    /** G: No access to call log - button opens permission request on phone. */
    data object NoAccess : PhoneCardModel
    /** D: Incoming. */
    data class Incoming(val title: String) : PhoneCardModel
    /** E: The conversation. */
    data class InCall(val title: String, val elapsedSec: Int) : PhoneCardModel
    /** Outbound, not answered yet. */
    data class Outgoing(val title: String) : PhoneCardModel
}

object PhoneLogic {
    /** Outgoing unanswerable show the first seconds: the states “answered” TelephonyManager does not give. */
    const val OUTGOING_PHASE_SEC = 5

    /**
     * @param callState CallState.*
     * @param callTitle name or number of the current call (may be empty if unknown)
     * @param ringed This call was RINGING (This is an entry, and OFFHOOK After that, the conversation.
     * @param offhookSec seconds from start OFFHOOK
     * @param calls Recent challenges, new firsts
     * @param missedSeenMs Missed not newer than this moment already viewed (tap on card)
     */
    fun model(
        permitted: Boolean, callState: Int, callTitle: String, ringed: Boolean, offhookSec: Int,
        calls: List<CallRecord>, missedSeenMs: Long,
    ): PhoneCardModel {
        when (callState) {
            CallState.RINGING -> return PhoneCardModel.Incoming(callTitle)
            CallState.OFFHOOK -> return if (ringed || offhookSec >= OUTGOING_PHASE_SEC)
                PhoneCardModel.InCall(callTitle, if (ringed) offhookSec else offhookSec - OUTGOING_PHASE_SEC)
            else PhoneCardModel.Outgoing(callTitle)
        }
        if (!permitted) return PhoneCardModel.NoAccess
        val missed = calls.filter { it.type == CallType.MISSED && it.timeMs > missedSeenMs }
        if (missed.isNotEmpty()) return PhoneCardModel.Missed(missed.size, missed.first())
        val last = calls.firstOrNull() ?: return PhoneCardModel.Empty
        return PhoneCardModel.LastCall(last)
    }

    /** "14:02" today, "yesterday", otherwise "31.10". */
    fun whenLabel(nowMs: Long, thenMs: Long, zone: ZoneId = ZoneId.systemDefault(), yesterday: String = "вчера"): String {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val then = Instant.ofEpochMilli(thenMs).atZone(zone)
        return when (then.toLocalDate()) {
            now -> then.format(DateTimeFormatter.ofPattern("HH:mm"))
            now.minusDays(1) -> yesterday
            else -> then.format(DateTimeFormatter.ofPattern("dd.MM"))
        }
    }

    fun timer(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)
}

/** Like in MediaSession: custom action with the name or icon "like" (Yandex Music, Spotify, YT Music). */
object MusicLogic {
    data class Action(val id: String, val name: String)

    private val LIKE = listOf("like", "love", "heart", "favorite", "favourite", "thumb", "нрав", "люб", "избран")
    private val UNLIKE = listOf("unlike", "unfavorite", "unfavourite", "remove", "убрать", "удалить")
    private val DISLIKE = listOf("dislike", "дизлайк", "не нрав")

    /** The action is "liked" among custom actions if the player gives it away. */
    fun findLike(actions: List<Action>): Action? = actions.firstOrNull { isLike(it) }

    fun isLike(a: Action): Boolean {
        val s = "${a.id} ${a.name}".lowercase()
        if (DISLIKE.any { it in s }) return false
        return LIKE.any { it in s }
    }

    /** The action is already included (the heart is colored): by the name of the type unlike / "remove from the favorites". */
    fun isLiked(a: Action): Boolean {
        val s = "${a.id} ${a.name}".lowercase()
        return UNLIKE.any { it in s }
    }

    /** Selection of the session: player (selected player among the players first), otherwise selected player, otherwise the first. */
    fun <T> pickSession(sessions: List<T>, pkgOf: (T) -> String, playing: (T) -> Boolean, preferred: String?): T? {
        val live = sessions.filter(playing)
        return live.firstOrNull { pkgOf(it) == preferred } ?: live.firstOrNull()
            ?: sessions.firstOrNull { pkgOf(it) == preferred }
            ?: sessions.firstOrNull()
    }
}
