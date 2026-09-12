package com.aliothmoon.maameow.mower

/** Read actual activity entries, never last-paused/resumed summary references. */
internal data class GameActivityState(
    val displayId: Int, val rootTaskId: Int?, val attached: Boolean,
    val state: String?, val visible: Boolean, val finishing: Boolean,
) {
    val present: Boolean get() = attached && !finishing && state != null &&
        state !in setOf("DESTROYED", "DESTROYING")
    val resumed: Boolean get() = present && state == "RESUMED" && visible

    companion object {
        fun parse(dump: String, packageName: String): List<GameActivityState> {
            val result = mutableListOf<GameActivityState>()
            var display = -1
            var task: Int? = null
            var entry: MutableList<String>? = null
            var entryDisplay = -1
            var entryTask: Int? = null
            fun finish() {
                val lines = entry ?: return
                val body = lines.joinToString("\n")
                result += GameActivityState(entryDisplay, entryTask,
                    lines.any { it.trimStart().startsWith("app=ProcessRecord{") },
                    Regex("(?m)^\\s*state=([A-Z_]+)\\b").find(body)?.groupValues?.get(1),
                    Regex("\\bmVisible=true\\b|\\bnowVisible=true\\b").containsMatchIn(body),
                    Regex("\\bfinishing=true\\b").containsMatchIn(body))
                entry = null
            }
            for (line in dump.lineSequence()) {
                val displayMatch = Regex("^Display #(\\d+)\\b").find(line)
                val taskMatch = Regex("^\\s*\\* Task\\{[^#]+#(\\d+)").find(line)
                val history = Regex("^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{").containsMatchIn(line)
                // Activity bodies end before the task/display summaries and other sections.
                if (displayMatch != null || taskMatch != null || history ||
                    (line.isNotBlank() && !line.startsWith("      "))) finish()
                if (displayMatch != null) { display = displayMatch.groupValues[1].toInt(); task = null }
                if (taskMatch != null) task = Regex("\\brootTaskId=(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
                    ?: taskMatch.groupValues[1].toInt()
                if (history && Regex("\\s${Regex.escape(packageName)}/").containsMatchIn(line)) {
                    entry = mutableListOf(); entryDisplay = display; entryTask = task
                } else entry?.add(line)
            }
            finish()
            return result
        }
    }
}
