package boid

import org.scalajs.dom
import org.scalajs.dom.{document, html}
import vecxt.all.*
import vecxt.BoundsCheck.DoBoundsCheck.yes

object BoidRenderer:
  import BoidConfig.*

  def createCanvas(): (html.Canvas, dom.CanvasRenderingContext2D) =
    val width = dom.window.innerWidth
    val height = dom.window.innerHeight
    val canvas = document.createElement("canvas").asInstanceOf[html.Canvas]
    canvas.width = width.toInt
    canvas.height = height.toInt
    document.body.appendChild(canvas)

    val ctx = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    ctx.fillStyle = "red"
    ctx.strokeStyle = "black"
    ctx.lineWidth = 1
    ctx.font = "16px Arial"
    ctx.textAlign = "center"
    ctx.textBaseline = "middle"

    (canvas, ctx)
  end createCanvas

  def drawBoids(
      ctx: dom.CanvasRenderingContext2D,
      positions: Matrix[Double],
      width: Double,
      height: Double
  ): Unit =
    ctx.clearRect(0, 0, width, height)
    for i <- 0 until numBoids do
      ctx.beginPath()
      ctx.arc(positions(i, 0), positions(i, 1), 5, 0, 2 * math.Pi)
      ctx.fill()
    end for
  end drawBoids
end BoidRenderer
