package solutions

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.DurationInt
import java.util.UUID


/**
 * EphemeralDriverFinder
 *
 * This actor tries to find a driver for one ride request.
 * It offers the ride to drivers one by one until one accepts.
 * If a driver rejects or times out, it tries the next driver.
 * When a driver accepts (or no drivers are left), this actor stops.
 */

object EphemeralDriverFinder:
  def apply(driverRefs: List[Driver], passengerId: UUID, pickup: Location, dropoff: Location, dispatcher: ActorRef[RideMsg]): Behavior[RideMsg] =
    Behaviors.setup { context =>

      // If no drivers are available, report failure and stop
    if driverRefs.isEmpty then
      dispatcher ! NoDriverFound(passengerId)
      Behaviors.stopped
    else
      val head = driverRefs.head
      val rest = driverRefs.tail

      // If the driver does not answer in time, we try the next one
      context.setReceiveTimeout(10.seconds, AnswerTimeout)

      // Offer the ride
      head.driverRef ! OfferRide(passengerId, pickup, dropoff, context.self)

      Behaviors.receiveMessage {
        // Driver accepted: inform dispatcher and stop
        case DriverAccepted(_, driverId) =>
            dispatcher ! DriverAccepted(passengerId, driverId)
            Behaviors.stopped

        // Driver rejected: try the next driver
        case DriverRejected =>
            apply(rest, passengerId, pickup, dropoff, dispatcher)

        // Timeout: try the next driver
        case AnswerTimeout =>
            context.log.info(s"* Finder * : timeout -> next driver")
            apply(rest, passengerId, pickup, dropoff, dispatcher)

        // Ride cancelled: stop immediately
        case RideCancelled =>
            Behaviors.stopped
        case _ =>
          Behaviors.same
        }
    }
