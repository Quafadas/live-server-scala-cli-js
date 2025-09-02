package io.github.quafadas.sjsls

import _root_.io.circe.*

sealed trait FrontendEvent derives Encoder.AsObject

case class KeepAlive() extends FrontendEvent derives Encoder.AsObject
case class PageRefresh() extends FrontendEvent derives Encoder.AsObject
