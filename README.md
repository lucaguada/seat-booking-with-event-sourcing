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

1. command handled: `BookSeat(row = Row1, seat = Seat2, user = Merlin)`
2. command handled: `BookSeat(row = Row1, seat = Seat1, user = Wart)`
3. command handled: `BookSeat(row = Row1, seat = Seat2, user = Merlin)`
4. async. command handled: `BookSeat(row = Row2, seat = Seat2, user = Wart)` -> version may be inconsistent because of (5)
5. async. command handled: `BookSeat(row = Row2, seat = Seat2, user = Merlin)` -> version may be inconsistent because of (4)
6. command handled: `BookSeat(row = Row1, seat = Seat1, user = Wart)`

```
1 -> Event Committed[id=de54786e-ad50-424c-8001-44cd36f9eb06, event=SeatBooked[row=Row1, seat=Seat2, user=Merlin], version=0, storedAt=2024-01-11T20:44:31.028653718] has been committed and emitted
2 -> Event Committed[id=f2994b63-2306-4c94-9cd1-16cba223d1d7, event=SeatBooked[row=Row1, seat=Seat1, user=Wart], version=1, storedAt=2024-01-11T20:44:31.039795412] has been committed and emitted
5 -> Event Committed[id=ea6c431a-4704-45eb-9bb2-eb63cdfcb9a2, event=SeatBooked[row=Row2, seat=Seat2, user=Merlin], version=2, storedAt=2024-01-11T20:44:31.045232365] has been committed and emitted
3 -> Can't book seat, command BookSeat[row=Row1, seat=Seat2, user=Merlin] with already booked seats
4 -> Can't commit event, event Uncommitted[event=SeatBooked[row=Row2, seat=Seat2, user=Wart], version=2] not consistent with version in event-store: 3
6 -> Can't book seat, command BookSeat[row=Row2, seat=Seat4, user=Wart] must book the middle seat
```
#### Summary:

In `case 1.` there's no issue Merlin can book seat 2 on row 1; the event-store version is 0.<br>
In `case 2.` there's no issue Wart can book seat 1 on row 1; the event-store version is 1.

As you can see from the output, instead of printing the error message thrown by `case 3.`, the result for `case 5.` is printed (because of virtual threads).<br>
In `case 5.` Merlin could book seat 2 on row 2; the event-store version is 2.<br>
In `case 4.` and `case 5.` there's a concurrency issue Merlin (`case 5.`) could book seat 2 on row 2, although Wart (`case 4.`) tried to book the same seat before Merlin.

In `case 3.` Merlin can't book the seat, since Wart already booked the same seat.<br>
In `case 6.` Wart can't book seat 4 on row 2, since a sided seat from the middle is already booked, therefore must book the middle seat. 

#### Keep in mind:

`case 4.` and `case 5.` are exchangeable due to the asynchronous nature of virtual threads, therefore try to run the example multiple times to see it happen.

The **optimistic lock** is implemented as a show-of-concept and most of the time works as-is, but you might face the casual and rare case where `case 4.` and `case 5.` are both valid.   


