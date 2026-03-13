# Distributed Ride-Sharing Simulation (Akka Actors)

## Overview
This project implements a simulation of an online **distributed ride-sharing platform** using an **actor-based microservice architecture**.

The system models interactions between passengers and drivers, handles ride requests, calculates prices, processes payments, and recovers system state after failures.

The implementation is built using **Akka Actors**, where each component of the system is represented as an independent actor that communicates through asynchronous message passing.

---

## Architecture

The system is composed of several actors, each responsible for a specific task.

### Dispatcher
Coordinates ride matching between passengers and drivers and manages the lifecycle of rides.

### Driver Actors
Represent drivers in the system and manage driver availability.

### Passenger Actors
Represent users requesting rides.

### PricingService
Calculates ride prices based on factors such as distance and rush hours.

### Bank
Processes payments and manages account balances.

### PassengerBlacklist
Prevents passengers with insufficient funds from requesting new rides.

### RideMonitor
Logs ride information and restores system state after failures.

### EphemeralDriverFinder
A temporary actor created to find a driver for a specific ride request.

### RideSharingSystem
Initializes the system and simulates platform activity by generating passengers, drivers, and ride requests.

