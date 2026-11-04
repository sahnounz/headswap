package com.yegorkapomidorka.headswap.ui

import android.content.Context

/**
 * Text for a user without Context: resource + format arguments, or a ready string (e.g., an exception message).
 * Pure logic (HomeLogic, Readiness) gives UiText, turns it into a string UI or notification via [get].
 */
sealed class UiText {
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText()

    data class Plural(val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText()

    data class Raw(val text: String) : UiText()

    fun get(ctx: Context): String = when (this) {
        is Res -> if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args.map { it.resolve(ctx) }.toTypedArray())
        is Plural -> ctx.resources.getQuantityString(id, count, *args.map { it.resolve(ctx) }.toTypedArray())
        is Raw -> text
    }

    companion object {
        fun res(id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun raw(text: String): UiText = Raw(text)

        /** The argument may itself be UiText: solved by the same context. */
        private fun Any.resolve(ctx: Context): Any = if (this is UiText) get(ctx) else this
    }
}
