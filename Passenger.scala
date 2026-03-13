package solutions

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import java.util.UUID
import scala.util.Random

/**
 * Passenger
 *
 * This actor represents one passenger.
 * The passenger can request a ride, cancel a ride, and ask for an estimated time.
 * The passenger moves between no ride, searching, and in-ride states.
 */

object Passenger:

  def apply(passengerName: String, passengerId: UUID, bank: ActorRef[RideMsg], dispatcher: ActorRef[RideMsg]): Behavior[RideMsg] =
    noRide(passengerName, passengerId, bank, dispatcher)

  // no active ride state
  private def noRide(passengerName: String, passengerId: UUID, bank: ActorRef[RideMsg], dispatcher: ActorRef[RideMsg]): Behavior[RideMsg] =
    Behaviors.receive { (context, msg) =>
      msg match

        // Passenger starts a new ride request
        case BeginNewRide(pickup, dropoff) =>
          dispatcher ! RequestRide(passengerId, passengerName, context.self, pickup, dropoff)
          searching(passengerName, passengerId, bank, dispatcher)

        case _ => Behaviors.same
    }

  // searching for a driver state
  private def searching(passengerName: String, passengerId: UUID, bank: ActorRef[RideMsg], dispatcher: ActorRef[RideMsg]): Behavior[RideMsg] =
    Behaviors.receive { (context, msg) =>
      msg match

        // Dispatcher assigned a driver
        case DriverAssigned(driverId)  =>
          inRide(passengerName, passengerId, bank, dispatcher,driverId)

        // No driver could be found, go back to noRide state
        case NoDriverFound =>
          noRide(passengerName, passengerId, bank, dispatcher)

        // Passenger cancels while searching
        case CancelMyCurrentRide =>
          dispatcher ! CancelRide(passengerId, UUID.randomUUID())
          Behaviors.same

        // Dispatcher cancelled the ride
        case RideCancelled  =>
          noRide(passengerName, passengerId, bank, dispatcher)
        case _ => Behaviors.same
    }

  // driver assigned => in ride state
  private def inRide(passengerName: String, passengerId: UUID, bank: ActorRef[RideMsg], dispatcher: ActorRef[RideMsg], driverId:UUID): Behavior[RideMsg] =
    Behaviors.receive { (context, msg) =>
      msg match

        // Ride ended: passenger rates the driver and goes no ride state
        case EndOfRide  =>
          val stars = Random.between(1, 6)
          dispatcher ! RateDriver(passengerId,driverId ,stars)
          noRide(passengerName, passengerId, bank, dispatcher)

        // Passenger cancels after a driver is assigned (dispatcher let it only before driver reaching the pickup)
        case CancelMyCurrentRide =>
          dispatcher ! CancelRide(passengerId, driverId)
          Behaviors.same

        // Dispatcher cancelled the ride
        case RideCancelled =>
          noRide(passengerName, passengerId, bank, dispatcher)

        // Passenger asks for estimated time
        case AskEstimatedTime =>
          dispatcher ! GetEstimatedTime(passengerId, driverId )
          Behaviors.same

        // passenger get back the estimated time
        case EstimatedTime(minutes) =>
          context.log.info(s"* Passenger * : $passengerName estimated time = $minutes minutes")
          Behaviors.same

        // Passenger cannot start a new ride while already in a ride
        case BeginNewRide(_, _) =>
          context.log.info(s"* Passenger * : $passengerName cannot start new ride while in ride")
          Behaviors.same

        case _ => Behaviors.same
    }
