package boid

import vecxt.all.*
import vecxt.BoundsCheck.DoBoundsCheck.yes

object BoidForces:
  import BoidConfig.*

  def limitForce(fx: Double, fy: Double, maxForce: Double): (Double, Double) =
    val magnitude = math.hypot(fx, fy)
    if magnitude > maxForce && magnitude > 0 then (fx * maxForce / magnitude, fy * maxForce / magnitude)
    else (fx, fy)
    end if
  end limitForce

  def wrapBoundaries(
      positions: Matrix[Double],
      boidIndex: Int,
      width: Double,
      height: Double
  ): Unit =
    if positions((boidIndex, 0)) < 0 then positions((boidIndex, 0)) = width
    end if
    if positions((boidIndex, 0)) > width then positions((boidIndex, 0)) = 0
    end if
    if positions((boidIndex, 1)) < 0 then positions((boidIndex, 1)) = height
    end if
    if positions((boidIndex, 1)) > height then positions((boidIndex, 1)) = 0
    end if
  end wrapBoundaries

  inline def calculateForces(
      positions: Matrix[Double],
      velocities: Matrix[Double],
      boidIndex: Int
  ): (Double, Double, Double, Double, Double, Double) =
    var sepX = 0.0
    var sepY = 0.0
    var sepCount = 0

    var aliX = 0.0
    var aliY = 0.0
    var aliCount = 0

    var cohX = 0.0
    var cohY = 0.0
    var cohCount = 0

    val boidX = positions((boidIndex, 0))
    val boidY = positions((boidIndex, 1))
    val boidVelX = velocities((boidIndex, 0))
    val boidVelY = velocities((boidIndex, 1))

    var j = 0
    while j < numBoids do
      if j != boidIndex then
        val dx = boidX - positions((j, 0))
        val dy = boidY - positions((j, 1))
        val distanceSquared = dx * dx + dy * dy

        if distanceSquared > 0 && distanceSquared < separationDistance * separationDistance then
          val distance = math.sqrt(distanceSquared)
          sepX += dx / distance
          sepY += dy / distance
          sepCount += 1
        end if

        if distanceSquared > 0 && distanceSquared < alignmentDistance * alignmentDistance then
          aliX += velocities((j, 0))
          aliY += velocities((j, 1))
          aliCount += 1
        end if

        if distanceSquared > 0 && distanceSquared < cohesionDistance * cohesionDistance then
          cohX += positions((j, 0))
          cohY += positions((j, 1))
          cohCount += 1
        end if
      end if
      j += 1
    end while

    // Process separation
    if sepCount > 0 then
      sepX /= sepCount
      sepY /= sepCount
      val magnitude = math.hypot(sepX, sepY)
      if magnitude > 0 then
        sepX = (sepX / magnitude) * maxSpeed - boidVelX
        sepY = (sepY / magnitude) * maxSpeed - boidVelY
      end if
      val (lx, ly) = limitForce(sepX, sepY, maxForce)
      sepX = lx
      sepY = ly
    end if

    // Process alignment
    if aliCount > 0 then
      aliX /= aliCount
      aliY /= aliCount
      val magnitude = math.hypot(aliX, aliY)
      if magnitude > 0 then
        aliX = (aliX / magnitude) * maxSpeed - boidVelX
        aliY = (aliY / magnitude) * maxSpeed - boidVelY
      end if
      val (lx, ly) = limitForce(aliX, aliY, maxForce)
      aliX = lx
      aliY = ly
    end if

    // Process cohesion
    if cohCount > 0 then
      cohX = cohX / cohCount - boidX
      cohY = cohY / cohCount - boidY
      val magnitude = math.hypot(cohX, cohY)
      if magnitude > 0 then
        cohX = (cohX / magnitude) * maxSpeed - boidVelX
        cohY = (cohY / magnitude) * maxSpeed - boidVelY
      end if
      val (lx, ly) = limitForce(cohX, cohY, maxForce)
      cohX = lx
      cohY = ly
    end if

    (sepX, sepY, aliX, aliY, cohX, cohY)
  end calculateForces
end BoidForces
