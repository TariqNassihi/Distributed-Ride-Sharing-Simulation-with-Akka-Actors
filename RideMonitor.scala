package solutions
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import java.util.UUID

/**
 * RideMonitor
 *
 * This actor stores ride statistics using event sourcing
 * It records completed rides and can answer queries like total revenue,
 * average ride time, busiest hour, and top driver.
 */

sealed trait MonitorCommand
case class LogEvent(msg: String) extends MonitorCommand
case class PersistRideCompleted(price: Double, driverId: UUID, steps: Int, hour: Int) extends MonitorCommand
case class GetTotalRevenue(replyTo: ActorRef[Double]) extends MonitorCommand
case class GetAverageRideTime(replyTo: ActorRef[Double]) extends MonitorCommand
case class GetBusiestHour(replyTo: ActorRef[Option[Int]]) extends MonitorCommand
case class GetTopDriver(replyTo: ActorRef[Option[UUID]]) extends MonitorCommand


sealed trait MonitorEvent
case class RideRecorded(price: Double, steps: Int, hour: Int, driverId: UUID) extends MonitorEvent
case class MonitorState(revenue: Double = 0.0, totalSteps: Int = 0, rides: Int = 0, hours: Map[Int, Int] = Map.empty, drivers: Map[UUID, Double] = Map.empty)


object RideMonitor:
  def apply(): Behavior[MonitorCommand] =
    Behaviors.setup { context =>
      EventSourcedBehavior[MonitorCommand, MonitorEvent, MonitorState](
        persistenceId = PersistenceId.ofUniqueId("ride-monitor"),
        emptyState = MonitorState(),

        // Command handler: reacts to commands and optionally persists events
        commandHandler = { (state, cmd) =>
          cmd match

            // Log a message (no persistence)
            case LogEvent(msg) =>
              context.log.info(s" *RideMonitor *: $msg")
              Effect.none

            // Record a completed ride by persisting an event
            case PersistRideCompleted(price, driverId, steps, hour) =>
              Effect.persist(RideRecorded(price, steps, hour, driverId))

            // Query: total revenue so far
            case GetTotalRevenue(replyTo) =>
              Effect.reply(replyTo)(state.revenue)

            // Query: average ride time
            case GetAverageRideTime(replyTo) =>
              val avg =
                if state.rides == 0 then 0.0
                else (state.totalSteps * 2).toDouble / state.rides
              Effect.reply(replyTo)(avg)

            // Query: busiest hour (hour with most rides)
            case GetBusiestHour(replyTo) =>
              val busiestHour =
                if state.hours.isEmpty then
                  None
                else
                  val (hour, _) = state.hours.maxBy { case (_, count) => count }
                  Some(hour)
              Effect.reply(replyTo)(busiestHour)

            // Query: best profitable driver
            case GetTopDriver(replyTo) =>
              val bestDriver =
               if state.drivers.isEmpty then
                None 
               else
                val (driverId, _) = state.drivers.maxBy { case (_, amount) => amount }
                Some(driverId)
              Effect.reply(replyTo)(bestDriver)
          
        },

        // updates state from persisted events
        eventHandler = { (state, event) =>
          event match
            case RideRecorded(price, steps, hour, driverId) =>
              state.copy(
                revenue = state.revenue + price,
                totalSteps = state.totalSteps + steps,
                rides = state.rides + 1,
                hours = state.hours.updated(hour, state.hours.getOrElse(hour, 0) + 1),
                drivers = state.drivers.updated(driverId, state.drivers.getOrElse(driverId,price) + price)
              )
        }
      )
    }