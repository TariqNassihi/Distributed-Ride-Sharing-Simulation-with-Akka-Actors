package solutions

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import scala.concurrent.duration.DurationLong
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random


// New types
case class Location(longitude: Double, latitude: Double)
case class Rating(avg: Double, count: Int)

enum RideStatus:
  case NotStarted, SearchingDriver, DriverComing, Started

case class Ride(passengerRef: ActorRef[RideMsg], passengerName: String, passengerId: UUID, pickup: Location,
                 dropoff: Location, price: Double, status: RideStatus)

case class Driver(driverRef: ActorRef[RideMsg], id: UUID, name: String, location: Location, minFare: Double,
                  rating: Rating, available: Boolean)


// Actors message exchange
trait RideMsg

// RideSharingSystem => Passengers
case class BeginNewRide(pickup: Location, dropoff: Location) extends RideMsg
case object CancelMyCurrentRide extends RideMsg // also for drivers
case object AskEstimatedTime extends RideMsg

//Passengers => Dispatcher
case class RequestRide(passengerId: UUID, passengerName: String, passengerRef: ActorRef[RideMsg],
                        pickup: Location, dropoff: Location) extends RideMsg

case class CancelRide(passengerId: UUID, driverId: UUID) extends RideMsg
case class GetEstimatedTime(passengerId: UUID, driverId: UUID) extends RideMsg
case class RateDriver(passengerId: UUID, driverId: UUID, stars: Int) extends RideMsg

// Dispatcher => passengers
case class DriverAssigned(driverId: UUID) extends RideMsg
case object RideCancelled extends RideMsg
case object EndOfRide extends RideMsg
case class NoDriverFound(passengerId: UUID) extends RideMsg
case class EstimatedTime(minutes: Int) extends RideMsg

// RideSharingSystem => Dispatcher
case class RegisterDriver(driverRef: ActorRef[RideMsg], driverId: UUID, driverName: String,
                           location: Location, minFare: Double) extends RideMsg


// Dispatcher => Driver
case class OfferRide(passengerId: UUID, pickup: Location, dropoff: Location, replyTo: ActorRef[RideMsg]) extends RideMsg
case class DriverAccepted(passengerId: UUID, driverId: UUID) extends RideMsg
case object DriverRejected extends RideMsg
case class UpdateDriverLocation(driverId: UUID, location: Location, passengerId: UUID) extends RideMsg
case object UpdateLocation extends RideMsg
case object AnswerTimeout extends RideMsg


// RideSharingSystem => Dispatcher
case object GoAvailable extends RideMsg
case object GoNotAvailable extends RideMsg
case object GoOffline extends RideMsg
case class DriverPaused(driverId: UUID) extends RideMsg
case class DriverResumed(driverId: UUID) extends RideMsg
case class DriverOffline(driverId: UUID) extends RideMsg
case class RideCompleted(passengerId: UUID, driverId: UUID) extends RideMsg


// Pricing: Dispatcher => PricingService => Dispatcher)
case class ComputePrice(passengerId: UUID, pickup: Location, dropoff: Location, replyTo: ActorRef[RideMsg]) extends RideMsg
case class PriceComputed(passengerId: UUID, price: Double) extends RideMsg


// Bank: Dispatcher => Bank => Dispatcher
case class InitPassenger(name: String, balance: Double) extends RideMsg
case class InitDriver(name: String, balance: Double) extends RideMsg
case class Pay(passengerId: UUID, passengerName: String, driverId: UUID, driverName: String, amount: Double, replyTo: ActorRef[RideMsg]) extends RideMsg
case class PaymentResult(passengerId: UUID, driverId: UUID, success: Boolean) extends RideMsg


// Blacklist: Dispatcher <=> BlacklistActor

case class BlacklistPassenger(passengerId: UUID) extends RideMsg
case class IsBlacklisted(passengerId: UUID, replyTo: ActorRef[RideMsg]) extends RideMsg
case class BlacklistResult(passengerId: UUID, blacklisted: Boolean) extends RideMsg


/**
 * RideSharingSystem(simulation)
 *
 * Spawns many drivers and passengers and starts rides at random intervals
 */

object RideSharingSystem:
  def apply(): Behavior[RideMsg] =
    Behaviors.setup { context =>
      val pricing = context.spawn(PricingService(), "PricingService")
      val bank = context.spawn(Bank(), "Bank")
      val monitor = context.spawn(RideMonitor(), "RideMonitor")
      val blacklist = context.spawn(PassengerBlacklist(), "Blacklist")
      val dispatcher = context.spawn(Dispatcher(pricing, bank, monitor, blacklist), "Dispatcher")

      val passengerCount = 15
      val driverCount = 10


      val passengers: List[ActorRef[RideMsg]] =
        (1 to 15).toList.map { i =>
          val name = s"p$i"
          bank ! InitPassenger(name, 200.0)
          val id = UUID.randomUUID()
          context.spawn(Passenger(name, id, bank, dispatcher), s"Passenger-$i")
        }

      val drivers: List[ActorRef[RideMsg]] =
        (1 to 10).toList.map { i =>
          val name = s"d$i"
          bank ! InitDriver(name, 0.0)
          val id = UUID.randomUUID()
          val start = Location(Random.between(0, 50), Random.between(0, 50))
          val minFare = Random.between(5, 20)
          val ref = context.spawn(Drivers(id, name, start, minFare, dispatcher), s"Driver-$i")
          dispatcher ! RegisterDriver(ref, id, name, start, minFare)
          ref
        }


      case object StartRandomRide extends RideMsg
      case object RandomDriverAction extends RideMsg

      def randomLoc(): Location =
        Location(Random.between(0, 50).toDouble, Random.between(0, 50).toDouble)

      Behaviors.withTimers { timers =>
        // Every 2 secondS: try to start a ride for a random passenger
        timers.startTimerAtFixedRate(StartRandomRide, 2.second)

        // Every 4 seconds: random driver availability/offline/online behavior
        timers.startTimerAtFixedRate(RandomDriverAction, 4.seconds)

        Behaviors.receiveMessage {

          // Start rides at random intervals for random passengers
          case StartRandomRide =>
            val passengerRef = passengers(Random.nextInt(passengers.size))
            val pickup = randomLoc()
            val dropoff = randomLoc()

            passengerRef ! BeginNewRide(pickup, dropoff)

            // Random action after starting a ride
            Random.nextInt(10) match
              case 0 | 1 | 2 | 4 =>
                // 40% chance: cancel the ride
                context.scheduleOnce(1.seconds, passengerRef, CancelMyCurrentRide)

              case 3 =>
                // 10% chance: ask estimated time
                context.scheduleOnce(2.seconds, passengerRef, AskEstimatedTime)
              case _ => ()
            Behaviors.same


          // Random driver actions (pause/resume/offline)
          case RandomDriverAction =>
            val driverRef = drivers(Random.nextInt(drivers.size))
            val r = Random.nextDouble()

            Random.nextInt(10) match
              case 0 | 1 =>
                // 20% => go not available
                driverRef ! GoNotAvailable
              case 2 | 3 =>
                // 20% => go not available
                driverRef ! GoAvailable

              case 4 | 5 =>
                // 20% :Driver cancels current ride (only works if driving to pickup)
                driverRef ! CancelMyCurrentRide
              case _ => ()
            Behaviors.same
        }
      }
    }


object RideSharing extends App:
  val system: ActorSystem[RideMsg] = ActorSystem(RideSharingSystem(), "RideSharing")
  Thread.sleep(200000)
  system.terminate()
  Await.ready(system.whenTerminated, Duration.Inf)
