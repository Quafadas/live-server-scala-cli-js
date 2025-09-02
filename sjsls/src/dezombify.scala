package io.github.quafadas.sjsls

import scala.concurrent.duration.*

import com.comcast.ip4s.Port

import fs2.io.process

import cats.effect.IO
import cats.effect.kernel.Resource

private[sjsls] def checkPortInUse(port: Port): IO[Boolean] =
  val osName = System.getProperty("os.name").toLowerCase
  val portInt = port.value

  if osName.contains("win") then
    val ps =
      s"Get-NetTCPConnection -LocalPort $portInt -ErrorAction SilentlyContinue | Measure-Object | Select-Object -ExpandProperty Count"

    process
      .ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps)
      .spawn[IO]
      .use {
        proc =>
          proc.stdout.through(fs2.text.utf8.decode).compile.string.map(_.trim.toIntOption.getOrElse(0) > 0)
      }
  else
    val sh = s"lsof -ti tcp:$portInt 2>/dev/null | wc -l"

    process
      .ProcessBuilder("sh", "-c", sh)
      .spawn[IO]
      .use {
        proc =>
          proc.stdout.through(fs2.text.utf8.decode).compile.string.map(_.trim.toIntOption.getOrElse(0) > 0)
      }
  end if
end checkPortInUse

private[sjsls] def dezombify(port: Port): Resource[IO, Unit] =
  val portInt = port.value

  val checkAndKill = for
    _ <- scribe.cats[IO].debug(s"Checking if port $portInt is in use before attempting cleanup")
    portInUse <- checkPortInUse(port)
    _ <-
      if portInUse then scribe.cats[IO].warn(s"Found zombie server on port $portInt - attempting to kill it")
      else scribe.cats[IO].debug(s"Port $portInt appears to be free, no zombie cleanup needed")
    _ <- if portInUse then killProcessesOnPort(port) else IO.unit
    _ <-
      if portInUse then
        for
          _ <- IO.sleep(scala.concurrent.duration.Duration.fromNanos(500_000_000)) // 500ms
          stillInUse <- checkPortInUse(port)
          _ <-
            if stillInUse then scribe.cats[IO].error(s"Port $portInt still appears to be in use after cleanup attempt")
            else scribe.cats[IO].debug(s"Successfully cleaned up port $portInt")
        yield ()
      else IO.unit
  yield ()

  checkAndKill.toResource
end dezombify

private def killProcessesOnPort(port: Port): IO[Unit] =
  val osName = System.getProperty("os.name").toLowerCase
  val portInt = port.value

  if osName.contains("win") then
    // Windows: try PowerShell Get-NetTCPConnection, fallback to netstat/taskkill
    val ps = s"""
    |if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    |  $$pids = Get-NetTCPConnection -LocalPort $portInt -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
    |  if ($$pids) {
    |    Write-Host "Found PIDs: $$pids"
    |    $$pids | ForEach-Object { Stop-Process -Id $$_ -Force }
    |    Write-Host "Killed processes on port $portInt"
    |  } else {
    |    Write-Host "No processes found on port $portInt"
    |  }
    |} else {
    |  $$lines = netstat -ano | Select-String ":$portInt\\s"
    |  $$pids = $$lines | ForEach-Object { ($$_ -split '\\s+')[-1] } | Select-Object -Unique
    |  if ($$pids) {
    |    Write-Host "Found PIDs: $$pids"
    |    $$pids | ForEach-Object { taskkill /F /PID $$_ }
    |    Write-Host "Killed processes on port $portInt"
    |  } else {
    |    Write-Host "No processes found on port $portInt"
    |  }
    |}
    |""".stripMargin

    for
      _ <- scribe.cats[IO].debug(s"Running Windows cleanup command for port $portInt")
      exitCode <- process
        .ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps)
        .spawn[IO]
        .use(_.exitValue)
      _ <- scribe.cats[IO].debug(s"Windows cleanup command completed with exit code $exitCode")
    yield ()
    end for
  else
    // macOS/Linux: use lsof if available, fallback to fuser
    val sh = s"""
    |if command -v lsof >/dev/null 2>&1; then
    |  pids=$$(lsof -ti tcp:$portInt 2>/dev/null)
    |  if [ -n "$$pids" ]; then
    |    echo "Found PIDs: $$pids"
    |    kill -9 $$pids
    |    echo "Killed processes on port $portInt"
    |  else
    |    echo "No processes found using lsof on port $portInt"
    |  fi
    |elif command -v fuser >/dev/null 2>&1; then
    |  echo "Using fuser to kill processes on port $portInt"
    |  fuser -k -TERM $portInt/tcp || echo "No processes killed with TERM signal"
    |  fuser -k -KILL $portInt/tcp || echo "No processes killed with KILL signal"
    |else
    |  echo "Neither lsof nor fuser available for port cleanup"
    |fi
    |""".stripMargin

    for
      _ <- scribe.cats[IO].debug(s"Running Unix cleanup command for port $portInt")
      // Use a timeout to prevent hanging
      result <- process
        .ProcessBuilder("sh", "-c", sh)
        .spawn[IO]
        .use(_.exitValue)
        .timeout(5.seconds)
        .handleErrorWith {
          err =>
            scribe.cats[IO].warn(s"Process cleanup timed out or failed: ${err.getMessage}") *> IO.pure(-1)
        }
      _ <- scribe.cats[IO].debug(s"Unix cleanup command completed with exit code $result")
    yield ()
    end for
  end if
end killProcessesOnPort
