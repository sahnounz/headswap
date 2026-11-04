// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

/** Permission adoption changes permission checks, never Linux identity or display ownership. */
internal object DelegatedDesktopPolicy {
    const val ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
    const val INJECT_EVENTS = "android.permission.INJECT_EVENTS"
    const val REAL_GET_TASKS = "android.permission.REAL_GET_TASKS"

    fun requireOwner(processUid: Int, appUid: Int, callerUid: Int) {
        if (appUid % 100000 < 10000 || processUid != appUid || callerUid != appUid) {
            throw SecurityException("Служба рабочего стола доступна только собственному приложению")
        }
    }

    fun requirePermissions(trustedDisplay: Boolean, input: Boolean) {
        if (!trustedDisplay || !input) throw SecurityException("Доступ рабочего стола завершён; повторно запустите службу доступа")
    }

    fun requireMode(mode: Int) {
        DesktopModePolicy.requireMode(mode)
        require(mode != DesktopModePolicy.SAMSUNG_DEX) { "Эта служба поддерживает только Android Desktop; Samsung DeX не поддерживается" }
    }

    data class Component(val packageName: String, val className: String, val ownActivity: Boolean)

    fun desktopComponent(mode: Int, displayId: Int, appPackage: String): Component {
        requireMode(mode)
        require(displayId > 0) { "Запуск на основном экране запрещён" }
        EmbeddedDisplayPolicy.requirePackage(appPackage)
        return Component(appPackage, DesktopModePolicy.ANDROID_HOME_CLASS, true)
    }

    /**
     * [visible]: On-Screen Task (TaskInfo.isVisible, API 32+); after moveTaskToBack, it remains first on the list, but is not visible.
     * [displayId]/[userId] = [UNKNOWN]if Android They did not give them to the application (hidden fields) TaskInfo).
     */
    data class Task(val displayId: Int, val userId: Int, val basePackage: String?, val topPackage: String?, val visible: Boolean = true)

    const val UNKNOWN = -1

    /**
     * null means unavailable/filtered, not an empty display. Never examine another display or user.
     * The task with an unknown display is taken into account only by package: in practice, the application has one task (restart)
     * re-uses it), so the score is correct until the same application is open and on the phone screen.
     */
    fun taskState(id: Int, userId: Int, packageName: String, permitted: Boolean, tasks: List<Task>?): Int {
        require(id > 0) { "Проверка основного экрана запрещена" }
        EmbeddedDisplayPolicy.requirePackage(packageName)
        if (!permitted || tasks == null) return -1
        val owned = tasks.filter {
            if (it.displayId == UNKNOWN || it.userId == UNKNOWN) it.basePackage == packageName || it.topPackage == packageName
            else it.displayId == id && it.userId == userId
        }
        val first = owned.firstOrNull()
        return when {
            first?.topPackage == packageName -> if (first.visible) 2 else 3
            owned.any { it.basePackage == packageName || it.topPackage == packageName } -> 1
            else -> 0
        }
    }

    /**
     * Screen task packs [id] for "Recent", fresh first, no repetitions. nullif the rights or list are not available or
     * at least one task is not identified screen or user: otherwise in the list would be the phone screen packages.
     */
    fun childPackages(id: Int, userId: Int, permitted: Boolean, tasks: List<Task>?): List<String>? {
        require(id > 0) { "Проверка основного экрана запрещена" }
        if (!permitted || tasks == null) return null
        if (tasks.any { it.displayId == UNKNOWN || it.userId == UNKNOWN }) return null
        return tasks.filter { it.displayId == id && it.userId == userId }
            .mapNotNull { it.basePackage ?: it.topPackage }.distinct()
    }
}

/** Every successful caller receives producer-release ACK, including concurrent/repeated destroy. */
internal class DelegatedShutdownGate {
    @Volatile var started = false
        private set
    private var failure: Throwable? = null

    fun requireOpen() {
        check(!started) { "Служба рабочего стола уже завершена" }
    }

    @Synchronized fun close(release: () -> Unit) {
        if (started) {
            failure?.let { throw it }
            return
        }
        started = true
        try { release() }
        catch (error: Throwable) {
            failure = error
            throw error
        }
    }
}
