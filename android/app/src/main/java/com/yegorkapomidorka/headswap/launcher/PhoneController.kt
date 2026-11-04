package com.yegorkapomidorka.headswap.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock

/**
 * Phone card details: call log and contacts (runtime- permissions issued from the phone), call status
 * from TelephonyManager, control via TelecomManager. Without permissions - empty state, nothing falls.
 */
@Suppress("DEPRECATION")
internal object PhoneController {
    private const val TAG = "HeadSwapLauncher"
    private const val CALLS_LIMIT = 64
    private const val CONTACTS_LIMIT = 400

    var calls by mutableStateOf<List<CallRecord>>(emptyList())
        private set
    var contacts by mutableStateOf<List<ContactRecord>>(emptyList())
        private set
    var logPermitted by mutableStateOf(false)
        private set
    var contactsPermitted by mutableStateOf(false)
        private set
    var callState by mutableIntStateOf(CallState.IDLE)
        private set
    var callTitle by mutableStateOf("")
        private set
    var ringed = false
        private set
    /** Seconds from the beginning of OFFHOOK; updated by tick(). */
    var offhookSec by mutableIntStateOf(0)
        private set

    private var offhookAt = 0L
    private var listener: PhoneStateListener? = null
    private var lastLoad = 0L

    /** Once in a couple of seconds from the root of launcher:, subscribe to calls, talk timer, re-read log. */
    fun tick(ctx: Context) {
        logPermitted = has(ctx, Manifest.permission.READ_CALL_LOG)
        contactsPermitted = has(ctx, Manifest.permission.READ_CONTACTS)
        if (listener == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(ctx, Manifest.permission.READ_PHONE_STATE))) {
            register(ctx)
        }
        if (callState == CallState.OFFHOOK) offhookSec = ((SystemClock.elapsedRealtime() - offhookAt) / 1000).toInt()
        val now = SystemClock.elapsedRealtime()
        if (callState == CallState.IDLE && now - lastLoad > 5000) {
            lastLoad = now
            io.execute { reloadCalls(ctx) }
        }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "headswap-launcher-phone").apply { isDaemon = true }
    }

    fun loadAsync(ctx: Context) {
        io.execute { reloadCalls(ctx); reloadContacts(ctx) }
    }

    /** Phone card permissions: the request opens the button "Open request on the phone" (MainActivity, cmd phone_card_perm). */
    val PERMISSIONS = listOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
    )

    private fun has(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun register(ctx: Context) {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onState(ctx, state, phoneNumber.orEmpty())
            }
        }
        // PhoneStateListener() takes the Looper creation thread: tick() comes with the main thread root of launcher
        runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE); listener = l }
            .onFailure { Log.w(TAG, "call state listener: ${it.javaClass.simpleName}") }
    }

    private fun onState(ctx: Context, state: Int, number: String) {
        val previous = callState
        when (state) {
            CallState.RINGING -> { ringed = true; callTitle = titleFor(ctx, number) }
            CallState.OFFHOOK -> {
                if (previous != CallState.OFFHOOK) offhookAt = SystemClock.elapsedRealtime()
                if (number.isNotEmpty()) callTitle = titleFor(ctx, number)
            }
            else -> { ringed = false; callTitle = ""; offhookSec = 0 }
        }
        callState = state
        if (state == CallState.IDLE) lastLoad = 0L // call ended: log reread on the nearest tick()
    }

    private fun titleFor(ctx: Context, number: String): String {
        if (number.isEmpty()) return ""
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return number
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: number
    }

    @SuppressLint("MissingPermission")
    fun reloadCalls(ctx: Context) {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) { calls = emptyList(); return }
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, CALLS_LIMIT)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val list = runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE), args, null,
            )?.use { c ->
                buildList {
                    while (size < CALLS_LIMIT && c.moveToNext()) add(CallRecord(c.getString(0).orEmpty(), c.getString(1), CallType.fromLog(c.getInt(2)), c.getLong(3)))
                }
            }.orEmpty()
        }.getOrElse { Log.w(TAG, "call log: ${it.javaClass.simpleName}"); emptyList() }
        if (list != calls) calls = list
    }

    fun reloadContacts(ctx: Context) {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) { contacts = emptyList(); return }
        val args = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, CONTACTS_LIMIT)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
        }
        val list = runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER), args, null,
            )?.use { c ->
                val seen = HashSet<Long>()
                buildList {
                    while (seen.size < CONTACTS_LIMIT && c.moveToNext()) {
                        val id = c.getLong(0)
                        if (seen.add(id)) add(ContactRecord(id, c.getString(1).orEmpty(), c.getString(2).orEmpty()))
                    }
                }
            }.orEmpty()
        }.getOrElse { Log.w(TAG, "contacts: ${it.javaClass.simpleName}"); emptyList() }
        if (list != contacts) contacts = list
    }

    // --- call management ---

    @SuppressLint("MissingPermission")
    fun accept(ctx: Context) {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.acceptRingingCall() }
    }

    /** Reject Incoming, End Conversation or Cancel Outgoing - Same endCall. */
    @SuppressLint("MissingPermission")
    fun end(ctx: Context) {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.endCall() }
    }

    /** Call without starting screens from launcher: placeCall, the call window will open on your phone. */
    @SuppressLint("MissingPermission")
    fun dial(ctx: Context, number: String) {
        if (number.isBlank() || !has(ctx, Manifest.permission.CALL_PHONE)) return
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.placeCall(Uri.fromParts("tel", number, null), Bundle()) }
            .onFailure { Log.w(TAG, "placeCall: ${it.javaClass.simpleName}") }
    }

    fun toggleMute(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.isMicrophoneMute = !am.isMicrophoneMute
        }
    }

    val micMuted: Boolean
        get() = runCatching { LauncherModel.context.getSystemService(AudioManager::class.java).isMicrophoneMute }.getOrDefault(false)
}
