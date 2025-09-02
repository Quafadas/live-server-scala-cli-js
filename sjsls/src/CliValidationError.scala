package io.github.quafadas.sjsls

import scala.util.control.NoStackTrace

private case class CliValidationError(message: String) extends NoStackTrace
