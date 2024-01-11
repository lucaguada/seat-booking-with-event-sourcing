# Seat Booking with Event Sourcing

A dead simple example of event-sourcing by using event-modelling with Java 21.

## The kata

1. Booking seats among multiple rows.
2. Booking seats in a single row by two concurrent commands that singularly do not violate any invariant rule and yet the final state is potentially invalid.

### Problem 1

I have two rows of seats related to two different streams of events. Each row has 5 seats. I want to book a seat in row 1 and a seat in row 2. I want to do this in a single 
transaction so that if just one of the claimed seats is already booked then the entire multiple-row transaction fails and no seats are booked at all.

#### Issues to solve:

1. Can you handle this in a transactional way?
2. Can it handle more rows?

### Problem 2

One invariant rule says that no booking can end up in leaving the only middle seat free in a row.
The invariant rule must be preserved even if two concurrent transactions try to book the two left seats and the two right seats independently so violating (together) this invariant.

### Issues to solve:

1. How to lock any row
2. Give timely feedback to the user

## How to: `run`

Install `OpenJDK 21` with `SDKMan` or any other tool you prefer.
Then if you're a Linux user like me, install `make` then: 

```sh
make run
```

otherwise:

```sh
java --source 21 --enable-preview SeatBooking.java
```

### Execution:

1. command handled: `BookSeat(row = Row1, seat = Seat2)`
2. command handled: `BookSeat(row = Row1, seat = Seat1)`
3. command handled: `BookSeat(row = Row1, seat = Seat2)`
4. async. command handled: `BookSeat(row = Row2, seat = Seat2)` -> version may be inconsistent because of (5)
5. async. command handled: `BookSeat(row = Row2, seat = Seat2)` -> version may be inconsistent because of (4)
6. command handled: `BookSeat(row = Row1, seat = Seat1)`

```
1 -> Event Stored[id=af5959ba-b5a3-4ad8-a685-fb71415eff2d, event=SeatBooked[row=Row1, seat=Seat2], version=0, storedAt=2024-01-11T16:49:53.625928904] has been committed and emitted
2 -> Event Stored[id=3c463597-7b2b-4057-aea0-ff6da683b904, event=SeatBooked[row=Row1, seat=Seat1], version=1, storedAt=2024-01-11T16:49:53.638233610] has been committed and emitted
4 -> Event Stored[id=81395001-7caf-44c2-a426-2e0730e66fde, event=SeatBooked[row=Row2, seat=Seat2], version=2, storedAt=2024-01-11T16:49:53.644297054] has been committed and emitted
3 -> Can't book seat, command BookSeat[row=Row1, seat=Seat2] with already booked seats
5 -> Can't commit event, event SeatBooked[row=Row2, seat=Seat2] with version 2 not consistent with stored-events version 3
6 -> Can't book seat, command BookSeat[row=Row2, seat=Seat4] must book the middle seat
```
