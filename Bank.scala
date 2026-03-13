package solutions

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

/**
 * Bank
 *
 * This actor simulates bank.
 * It stores balances for passengers and drivers
 * and processes payments after a ride is completed.
 */
object Bank:

  def apply(): Behavior[RideMsg] =
    Behaviors.setup { context =>

      // Passenger balances map (passengerName, Balance)
      var passengers: Map[String, Double] = Map.empty

      // Driver balances map (driverName, Balance)
      var drivers: Map[String, Double] = Map.empty

      Behaviors.receiveMessage {

        // Create a new passenger bank account if the passenger has not already an account
        case InitPassenger(name, balance) =>
          if passengers.contains(name) then
            context.log.info(s"* Bank *: the name $name already has a bank account")
          else
            passengers = passengers.updated(name, balance)
            context.log.info(s"* Bank *: new passenger=$name balance=$balance")
          Behaviors.same

        // Create a new driver bank account if the driver has not already an account
        case InitDriver(name, balance) =>
          if drivers.contains(name) then
            context.log.info(s"* Bank *: the name $name already has a bank account")
          else
            drivers = drivers.updated(name, balance)
            context.log.info(s"* Bank *: driver=$name balance=$balance")
          Behaviors.same

        // Process payment from passenger to driver
        case Pay(passengerId, passengerName, driverId, driverName, amount, replyTo) =>
          val passengerBalance = passengers.getOrElse(passengerName, 0.0)

          // Check if passenger has enough balance
          if passengerBalance >= amount then
            // Subtract money from passenger
            passengers = passengers.updated(passengerName, passengerBalance - amount)

            // Add money to driver
            val driverBalance = drivers.getOrElse(driverName, 0.0)
            drivers = drivers.updated(driverName, driverBalance + amount)
            replyTo ! PaymentResult(passengerId, driverId, true)
          else
            // Payment failed (not enough balance)
            replyTo ! PaymentResult(passengerId, driverId, false)

          Behaviors.same
        case _ =>
          Behaviors.same
      }
    }
