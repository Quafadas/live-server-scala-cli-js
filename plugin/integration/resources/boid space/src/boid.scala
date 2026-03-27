package boid

import org.scalajs.dom
import scala.util.Random
import vecxt.all.*
import vecxt.BoundsCheck.DoBoundsCheck.yes

import BoidConfig.*
import BoidForces.*
import BoidRenderer.*

@main def main =
  println("aha go where no one has gone before..")

  val positions = Matrix.zeros[Double](numBoids, 2)
  val velocities = Matrix.zeros[Double](numBoids, 2)
  val accelerations = Matrix.zeros[Double](numBoids, 2)

  val (canvas, ctx) = createCanvas()
  val width = canvas.width.toDouble
  val height = canvas.height.toDouble

  println("Canvas initialized with dimensions: " + width + "x" + height)

  // Initialize boids with random positions and velocities
  for i <- 0 until numBoids do
    positions((i, 0)) = Random.nextDouble() * width
    positions((i, 1)) = Random.nextDouble() * height
    velocities((i, 0)) = (Random.nextDouble() - 0.5) * maxSpeed
    velocities((i, 1)) = (Random.nextDouble() - 0.5) * maxSpeed

  inline def animate(): Unit =
    // Calculate all forces in one pass
    var i = 0
    while i < numBoids do
      val (sepX, sepY, aliX, aliY, cohX, cohY) =
        calculateForces(positions, velocities, i)

      accelerations((i, 0)) = sepX * 1.5 + aliX * 1.0 + cohX * 1.0
      accelerations((i, 1)) = sepY * 1.5 + aliY * 1.0 + cohY * 1.0
      i += 1

    // Update boids
    for i <- 0 until numBoids do
      velocities((i, 0)) = velocities((i, 0)) + accelerations((i, 0))
      velocities((i, 1)) = velocities((i, 1)) + accelerations((i, 1))

      // Limit speed
      val speed = math.hypot(velocities(i, 0), velocities(i, 1))
      if speed > maxSpeed then
        velocities((i, 0)) = velocities((i, 0)) * maxSpeed / speed
        velocities((i, 1)) = velocities((i, 1)) * maxSpeed / speed

      positions((i, 0)) = positions((i, 0)) + velocities((i, 0))
      positions((i, 1)) = positions((i, 1)) + velocities((i, 1))

      wrapBoundaries(positions, i, width, height)

    drawBoids(ctx, positions, width, height)

  dom.window.setInterval(() => animate(), 25)
