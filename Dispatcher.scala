package solutions

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.Behaviors
import java.time.LocalTime
import java.util.UUID
import scala.math.{abs, max}

/**
 * Dispatcher
 *
 * The Dispatcher is the main coordinator
 * It maintains ride state and controls the ride lifecycle.
 *
 * If the Dispatcher fails, it is automatically restarted
 * to continue handling new ride requests.
 */

object Dispatcher:

  def apply(pricing: ActorRef[RideMsg], bank: ActorRef[RideMsg], monitor: ActorRef[MonitorCommand], blacklist: ActorRef[RideMsg]): Behavior[RideMsg] =
    Behaviors
      .supervise {
        Behaviors.setup[RideMsg] { context =>

          // Helper functions

          // Distance between two locations (Euclidean)
          def distance(a: Location, b: Location): Double =
            val dx = a.longitude - b.longitude
            val dy = a.latitude - b.latitude
            Math.sqrt(dx * dx + dy * dy)

          // Steps between two locations (used for estimated time + monitor stats)
          def stepsBetween(a: Location, b: Location): Int =
            max(abs(a.longitude - b.longitude), abs(a.latitude - b.latitude)).toInt

          // Update average rating when a new rating is added
          def newAverage(avg: Double, count: Int, newElem: Double): Rating =
            val newAvg = (avg * count + newElem) / (count + 1)
            Rating(newAvg, count + 1)

          var drivers: Map[UUID, Driver] = Map.empty  // All known drivers (driverId, Driver)
          var rides: Map[UUID, Ride] = Map.empty //Active rides (passengerId, ride)
          var finders: Map[UUID, ActorRef[RideMsg]] = Map.empty //Temporary finder actors per passenger(Id, actorRef)

          Behaviors.receiveMessage {

            // A driver is registered (= adding to driver map) and becomes available in the system.
            case RegisterDriver(driverRef, driverId, driverName, location, minFare) =>
              val d = Driver(driverRef, driverId, driverName, location, minFare, Rating(0.0, 0), true)
              drivers = drivers.updated(driverId, d)
              monitor ! LogEvent(s"Driver online name=$driverName loc=$location minFare=$minFare")
              Behaviors.same

            // Passenger asks for a ride, ask the blacklist actor if passenger is blocked
            case RequestRide(passengerId, passengerName, passengerRef, pickup, dropoff) =>
              monitor ! LogEvent(s" Passenger= $passengerName requested a ride from $pickup to $dropoff")
              rides = rides.updated(passengerId, Ride(passengerRef, passengerName, passengerId, pickup, dropoff, 0.0, RideStatus.NotStarted))
              blacklist ! IsBlacklisted(passengerId, context.self)
              Behaviors.same

            // Result from blacklist actor
            // - If blacklisted: cancel ride immediately
            // - If not: ask pricing service to compute the ride price.
            case BlacklistResult(passengerId, isBlacklisted) =>
              if rides.contains(passengerId) then
                val ride = rides(passengerId)
                if isBlacklisted then
                  monitor ! LogEvent(s"passenger=${ride.passengerName} is BLACKLISTED -> rejected")
                  rides = rides - passengerId
                  ride.passengerRef ! RideCancelled
                else
                  pricing ! ComputePrice(passengerId, ride.pickup, ride.dropoff, context.self)

              Behaviors.same

            // Price is computed by pricing service, select compatible drivers:
            // - must be available
            // - price must be >= driver.minFare
            // - choose closest first
            // Then we spawn a finder actor to offer the ride to drivers one by one.
            case PriceComputed(passengerId, price) =>
              if rides.contains(passengerId) then
                val ride = rides(passengerId)
                val updated = ride.copy(price = price, status = RideStatus.SearchingDriver)// new status and price
                rides = rides.updated(passengerId, updated)
                monitor ! LogEvent(s"ride priced for passenger=${ride.passengerName} price=$price")
                val compatible =
                  drivers.values.toList
                    .filter(_.available)
                    .filter(d => price >= d.minFare)
                    .sortBy(d => distance(updated.pickup, d.location))
                val finder = context.spawnAnonymous(EphemeralDriverFinder(compatible, passengerId, updated.pickup, updated.dropoff, context.self))
                finders = finders.updated(passengerId, finder)
              Behaviors.same


            // A driver accepted (message comes from the Finder), mark ride as coming and notify passenger about assigned driver
            case DriverAccepted(passengerId, driverId) =>
              if rides.contains(passengerId)  then
                val ride = rides(passengerId)
                val ride2 = ride.copy(status = RideStatus.DriverComing)
                rides = rides.updated(passengerId, ride2)
                ride.passengerRef ! DriverAssigned(driverId)
                monitor ! LogEvent(s"driver found for passenger=${ride.passengerName} with driver ${drivers(driverId).name} ")
                if finders.contains(passengerId) then
                  finders = finders - passengerId
              Behaviors.same

            // Finder reports that no driver accepted or none were compatible.
            case NoDriverFound(passengerId) =>
              monitor ! LogEvent(s"no driver found for $passengerId ")
              if finders.contains(passengerId) then
                finders = finders - passengerId

              if rides.contains(passengerId) then
                val ride = rides(passengerId)
                ride.passengerRef ! RideCancelled
                rides = rides - passengerId
              Behaviors.same


            // Driver sends location updates while working
            // store the new driver location
            // If driver reaches pickup while "DriverComing", then ride becomes "Started".
            case UpdateDriverLocation(driverId, newLoc, passengerId) =>
              if drivers.contains(driverId) then
                val driver = drivers(driverId)
                monitor ! LogEvent(s"new location for driver ${driver.name}:  $newLoc ")
                drivers = drivers.updated(driverId, driver.copy(location = newLoc))

              if rides.contains(passengerId) then
                val ride = rides(passengerId)
                if ride.status == RideStatus.DriverComing && newLoc == ride.pickup then
                  rides = rides.updated(passengerId, ride.copy(status = RideStatus.Started))
              Behaviors.same


            // Driver reached dropoff, asks bank to pay driver.
            case RideCompleted(passengerId, driverId) =>
              if rides.contains(passengerId) && drivers.contains(driverId) then
                val ride = rides(passengerId)
                val driver = drivers(driverId)
                monitor ! LogEvent(s"ride completed for passenger=${ride.passengerName} driver=${driver.name}")
                bank ! Pay(passengerId, ride.passengerName,driverId ,driver.name, ride.price, context.self)
              Behaviors.same

            // Bank responds: payment succeeded or failed
            // If payment failed, passenger is blacklisted.
            case PaymentResult(passengerId, driverId, success) =>
              if rides.contains(passengerId) && drivers.contains(driverId) then
                val ride = rides(passengerId)
                val driver = drivers(driverId)
                monitor ! LogEvent(s"payment passenger=${ride.passengerName} $success")
                ride.passengerRef ! EndOfRide
                if !success then blacklist ! BlacklistPassenger(passengerId)
              Behaviors.same

            // Passenger rates the driver at end of ride,update driver's rating
            case RateDriver(passengerId,driverId ,stars) =>
              if rides.contains(passengerId) && drivers.contains(driverId) then
                val ride = rides(passengerId)
                val driver = drivers(driverId)
                val newAvg = newAverage(driver.rating.avg, driver.rating.count, stars)
                drivers = drivers.updated(driverId, driver.copy(rating = newAvg))
                monitor ! LogEvent(s"driver rated name=${driver.name} new avg=${newAvg.avg}")
                val hour = LocalTime.now().getHour
                val steps = stepsBetween(ride.pickup, ride.dropoff)
                monitor ! PersistRideCompleted(ride.price, driver.id, steps, hour)
                rides = rides - passengerId
              Behaviors.same


            // Passenger or driver asks to cancel, what happens depends on the current ride status.
            case CancelRide(passengerId, driverId) =>
              if rides.contains(passengerId) then
                val ride = rides(passengerId)
                ride.status match

                  // If searching: stop finder,remove ride.
                  case RideStatus.SearchingDriver =>
                    if finders.contains(passengerId) then
                      val finder = finders(passengerId)
                      finder ! RideCancelled
                      finders = finders - passengerId
                      monitor ! LogEvent(s"ride cancelled (searching) passenger=${ride.passengerName}")
                    ride.passengerRef ! RideCancelled
                    rides = rides - passengerId

                  // If driver is coming: notify driver + passenger, remove ride.
                  case RideStatus.DriverComing =>
                    monitor ! LogEvent(s"ride cancelled (driver coming) passenger=${ride.passengerName} driver ${drivers(driverId).name}")
                    rides = rides - passengerId
                    ride.passengerRef ! RideCancelled
                    drivers(driverId).driverRef ! RideCancelled

                  // If ride already started, cancellation is refused.
                  case _ =>
                    monitor ! LogEvent(s"cannot cancel (already started) passenger=${ride.passengerName}")
              Behaviors.same

            // Passenger asks estimated time,compute time from driver current location to pickup or dropoff
            case GetEstimatedTime(passengerId, driverId) =>
              if rides.contains(passengerId) && drivers.contains(driverId) then
                val ride = rides(passengerId)
                val driver = drivers(driverId)
                if ride.status == RideStatus.DriverComing || ride.status == RideStatus.Started then
                  val dest = if ride.status == RideStatus.DriverComing then ride.pickup else ride.dropoff
                  val time = stepsBetween(driver.location,dest) * 2
                  ride.passengerRef ! EstimatedTime(time)
                  context.log.info( s"passenger: ${ride.passengerName} => $time minutes to dropoff")
              Behaviors.same

            //Driver takes a break: mark driver not available.
            case DriverPaused(driverId) =>
              if drivers.contains(driverId) then
                val driver = drivers(driverId)
                drivers = drivers.updated(driverId, driver.copy(available = false))
                monitor ! LogEvent(s"Driver ${driver.name} takes a break")
              Behaviors.same

            // Driver resumed: mark driver available again.
            case DriverResumed(driverId) =>
              if drivers.contains(driverId) then
                val driver = drivers(driverId)
                drivers = drivers.updated(driverId, driver.copy(available = true))
                monitor ! LogEvent(s"driver name=${driver.name} finished pause")
              Behaviors.same

            // Driver offline: remove from drivers list.
            case DriverOffline(driverId) =>
              if drivers.contains(driverId) then
                val driver = drivers(driverId)
                drivers = drivers - driverId
                monitor ! LogEvent(s"driver offline name=${driver.name}")
              Behaviors.same
            case _ =>
              Behaviors.same
          }
        }
      }
      .onFailure[Exception](SupervisorStrategy.restart)