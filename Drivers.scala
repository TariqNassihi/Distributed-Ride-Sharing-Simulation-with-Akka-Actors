package solutions

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import java.util.UUID
import scala.concurrent.duration.DurationInt



/**
 * Driver
 *
 * This actor represents one driver in the system.
 * It can be available, not available, or working on a ride.
 * When working, it moves step by step from start => pickup => dropoff.
 */
object Drivers:

  def apply(driverId: UUID, driverName: String, startLocation: Location, minFare: Double, dispatcher: ActorRef[RideMsg]): Behavior[RideMsg] =
    available(driverId, driverName, dispatcher, startLocation)

  // Not available state: driver refuses all ride offers
  private def notAvailable(driverId: UUID, driverName: String, dispatcher: ActorRef[RideMsg], location: Location): Behavior[RideMsg] =
    Behaviors.receive { (context, msg) =>
      msg match
        case OfferRide(_, _, _, replyTo) =>
          replyTo ! DriverRejected
          Behaviors.same

        // Driver becomes available again
        case GoAvailable =>
          dispatcher ! DriverResumed(driverId)
          available(driverId, driverName, dispatcher, location)

        // Driver goes offline (stops the actor)
        case GoOffline =>
          dispatcher ! DriverOffline(driverId)
          Behaviors.stopped

        case _ => Behaviors.same
    }

  // Available state: driver can accept rides
  private def available(driverId: UUID, driverName: String, dispatcher: ActorRef[RideMsg], location: Location): Behavior[RideMsg] =
    Behaviors.receive { (context, msg) =>
      msg match

        // Accept the ride and start working
        case OfferRide(passengerId, pickup, dropoff, replyTo) =>
          replyTo ! DriverAccepted(passengerId, driverId)
          working(driverId, driverName, dispatcher, location, passengerId, pickup, dropoff)

        // Driver pauses (becomes not available)
        case GoNotAvailable =>
          dispatcher ! DriverPaused(driverId)
          notAvailable(driverId, driverName, dispatcher, location)

        // Driver goes offline (stops the actor)
        case GoOffline =>
          dispatcher ! DriverOffline(driverId)
          Behaviors.stopped
        case _ => Behaviors.same
    }

  // Working state: driver is on a ride and moves every 2 seconds
  private def working(driverId: UUID, driverName: String, dispatcher: ActorRef[RideMsg], start: Location, passengerId: UUID, pickup: Location, dropoff: Location): Behavior[RideMsg] =
    Behaviors.withTimers { timers =>
      // Trigger UpdateLocation every 2 seconds
      timers.startTimerAtFixedRate(UpdateLocation, 2.seconds)

      // Move one step closer to the destination (+/- 1 in each axis)
      def goToDestination(current: Location, destination: Location): Location =
        val newLongitude =
          if current.longitude < destination.longitude then current.longitude + 1
          else if current.longitude > destination.longitude then current.longitude - 1
          else current.longitude

        val newLatitude =
          if current.latitude < destination.latitude then current.latitude + 1
          else if current.latitude > destination.latitude then current.latitude - 1
          else current.latitude

        Location(newLongitude, newLatitude)

      // Current location
      var begin = start

      // Current target: first pickup, then dropoff
      var destination = pickup

      Behaviors.receive { (context, msg) =>
        msg match

          // move and send updated location to dispatcher
          case UpdateLocation =>
            val newLoc = goToDestination(begin, destination)
            dispatcher ! UpdateDriverLocation(driverId, newLoc, passengerId)

            // If pickup reached => go to dropoff
            if newLoc == pickup then
              destination = dropoff
              begin = newLoc
              Behaviors.same

            // If dropoff reached => ride completed
            else if newLoc == dropoff then
              dispatcher ! RideCompleted(passengerId, driverId)
              timers.cancel(UpdateLocation)
              available(driverId, driverName, dispatcher, newLoc)
            else  // Still traveling
              begin = newLoc
              Behaviors.same

          // If a new ride is offered while working, reject it
          case OfferRide(_, _, _, replyTo) =>
            replyTo ! DriverRejected
            Behaviors.same

          // Driver asks to cancel the current ride (dispatcher let it only before reaching the pickup)
          case CancelMyCurrentRide =>
            dispatcher ! CancelRide(passengerId, driverId)
            Behaviors.same

          // Dispatcher cancelled the ride: stop movement and become available
          case RideCancelled =>
            timers.cancel(UpdateLocation)
            available(driverId, driverName, dispatcher, begin)

          // Driver asks to be not available (dispatcher let it only before reaching the pickup)
          case GoNotAvailable =>
            dispatcher ! DriverPaused(driverId)
            Behaviors.same

          case _ => Behaviors.same
      }
    }
