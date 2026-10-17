package com.coooolfan.xiaomialbumsyncer.utils

import java.nio.file.Files
import java.nio.file.Path

/** Unlink operations require physical paths, including every ancestor. */
internal fun noSymlinks(path: Path): Boolean {
    var cursor: Path? = path.toAbsolutePath().normalize()
    while (cursor != null) {
        if (Files.isSymbolicLink(cursor)) return false
        cursor = cursor.parent
    }
    return true
}
