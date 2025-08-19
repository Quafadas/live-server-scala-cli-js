package io.github.quafadas.sjsls

import _root_.io.circe.*
import _root_.io.circe.Encoder

sealed trait FrontendEvent derives Encoder.AsObject

case class KeepAlive() extends FrontendEvent derives Encoder.AsObject
case class PageRefresh() extends FrontendEvent derives Encoder.AsObject
