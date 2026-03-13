package solutions

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import java.util.UUID


/**
 * PassengerBlacklist
 *
 * The PassengerBlacklist keeps track of passengers
 * that are not allowed to request rides.
 * It answers blacklist checks and updates the blacklist.
 *
 */
object PassengerBlacklist:
  def apply(): Behavior[RideMsg] =
    Behaviors.setup { context =>

    // Set of blacklisted passenger IDs
    var blacklist: Set[UUID] = Set.empty

      // Add a passenger to the blacklist
      Behaviors.receiveMessage {
        case BlacklistPassenger(passengerId) =>
          blacklist = blacklist + passengerId
          Behaviors.same

        // Check if a passenger is blacklisted, result is sent back to the dispatcher
        case IsBlacklisted(passengerId, replyTo) =>
          replyTo ! BlacklistResult(passengerId, blacklist.contains(passengerId))
          Behaviors.same

        case _ =>
          Behaviors.same
      }
    }
