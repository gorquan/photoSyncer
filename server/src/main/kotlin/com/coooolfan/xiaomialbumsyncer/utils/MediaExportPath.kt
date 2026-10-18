package com.coooolfan.xiaomialbumsyncer.utils

import java.nio.file.Path

/** Receipt paths must stay inside their layout's literal root and outside publication state. */
internal fun safeMediaExport(path: Path, layout: String, stateRoot: Path): Boolean = runCatching {
    val expression = layout.trim()
    val token = expression.indexOf("${'$'}{")
    val base = if (token < 0) Path.of(expression) else {
        val literal = expression.substring(0, token)
        require(literal.isNotBlank())
        if (literal.endsWith('/')) Path.of(literal) else Path.of(literal).parent ?: error("No fixed directory")
    }
    val boundary = base.toAbsolutePath().normalize()
    boundary.parent != null && path != boundary && path.startsWith(boundary) && !path.startsWith(stateRoot) &&
        !stateRoot.startsWith(boundary) && noSymlinks(boundary)
}.getOrDefault(false)
