package solutions

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import java.time.LocalTime


/**
 * PricingService
 *
 * This actor calculates the price of a ride.
 * The price is based on the distance between
 * pickup and dropoff, with a higher price
 * during rush hours.
 */

object PricingService:

  // Helper: Calculate the distance between two locations
  def distance(a: Location, b: Location): Double =
    val dx = a.longitude - b.longitude
    val dy = a.latitude - b.latitude
    Math.sqrt(dx * dx + dy * dy)

  def apply(): Behavior[RideMsg] =
    Behaviors.receive { (context, message) =>
      message match

        // compute the price of a ride
        case ComputePrice(passengerId, pickup, dropoff, replyTo) =>
          val steps = distance(pickup, dropoff)

          // Get current hour of the day
          val hour = LocalTime.now().getHour

          // Use higher pricing during rush hours (5 per unit during rush, 2 during normal hours)
          val multiplier = if (hour >= 7 && hour <= 9) || (hour >= 16 && hour <= 18) then 5.0 else 2.0

          // Final ride price
          val price = steps * multiplier
          replyTo ! PriceComputed(passengerId, price)
          Behaviors.same
        case _ =>
          Behaviors.same
    }
