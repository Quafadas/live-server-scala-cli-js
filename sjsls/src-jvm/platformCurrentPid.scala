package io.github.quafadas.sjsls

private[sjsls] def currentProcessPid(): Long = ProcessHandle.current().pid()
