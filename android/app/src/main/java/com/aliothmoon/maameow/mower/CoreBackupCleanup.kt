package com.aliothmoon.maameow.mower

import java.io.File
import java.nio.file.Files
import java.util.Comparator

internal object CoreBackupCleanup {
    fun retire(root: File, verified: String): Boolean {
        require(verified.matches(Regex("[a-f0-9]{64}")))
        var complete = true
        for (candidate in root.listFiles().orEmpty()) {
            val match = Regex("([a-f0-9]{64})(?:\\.zip|\\.install)?").matchEntire(candidate.name) ?: continue
            if (match.groupValues[1] == verified) continue
            // Files.walk does not follow symlinks; user data and JNA directories
            // are deliberately outside this allowlist.
            runCatching {
                Files.walk(candidate.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }.onFailure { complete = false }
        }
        return complete
    }
}
