import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

enum Row {Row1, Row2, Row3, Row4, Row5}

enum Seat {Seat1, Seat2, Seat3, Seat4, Seat5}

enum User {Merlin, Wart} // you know why, right? ;)

sealed interface Event<EVENT extends Event<EVENT>> {
  record SeatBooked(Row row, Seat seat, User user) implements Event<SeatBooked> {}

  record Uncommitted<EVENT extends Event<EVENT>>(EVENT event, int version) {}

  record Committed<EVENT extends Event<EVENT>>(UUID id, EVENT event, int version, LocalDateTime storedAt) implements Event<SeatBooked> {
    Committed(EVENT event, int version) {
      this(UUID.randomUUID(), event, version, LocalDateTime.now());
    }
  }
}

sealed interface Command<COMMAND extends Command<COMMAND>> {
  record BookSeat(Row row, Seat seat, User user) implements Command<BookSeat> {}
}

/**
 * event-store
 */
final Queue<Event.Committed<?>> eventStore = new ArrayDeque<>();

/**
 * load current events list
 *
 * @return current events list
 */
List<Event.Committed<?>> loadEvents() {return eventStore.stream().toList();}

/**
 * append an event to the event-store
 *
 * @param event event to append
 * @return appended event
 */
<EVENT extends Event<EVENT>> Event.Committed<EVENT> appendEvent(Event.Uncommitted<EVENT> event) {
  return switch (new Event.Committed<>(event.event, event.version)) {
    case Event.Committed<EVENT> committed when committed.version == eventStore.size() -> {
      eventStore.add(committed);
      yield committed;
    }
    default -> throw new IllegalStateException(STR."Can't append uncommitted event, event \{event} not consistent with version in event-store: \{eventStore.size()}");
  };
}

/**
 * invariant implementation for already booked seats
 *
 * @param events loaded committed-events
 * @param brow   booking row
 * @param bseat  booking seat
 * @return true when row and seat are already booked false otherwise
 */
boolean alreadyBooked(List<Event.Committed<?>> events, Row brow, Seat bseat) {
  return events.stream().anyMatch(stored -> switch (stored.event) {
    case Event.SeatBooked(var row, var seat, _) -> row == brow && seat == bseat;
    default -> false;
  });
}

/**
 * invariant implementation for making the middle seat mandatory when a sided seat has been booked
 *
 * @param events loaded committed-events
 * @param brow   booking row
 * @param bseat  booking seat
 * @return true when the middle-seat is mandatory, false otherwise
 */
boolean middleSeatMandatory(List<Event.Committed<?>> events, Row brow, Seat bseat) {
  return bseat != Seat.Seat1 && bseat != Seat.Seat5 && events.stream()
    .filter(committed -> committed.event instanceof Event.SeatBooked(var row, _, _) && row == brow)
    .anyMatch(committed -> committed.event instanceof Event.SeatBooked(_, var seat, _) && (seat == Seat.Seat2 || seat == Seat.Seat4));
}

/**
 * commit an uncommitted event
 * @param uncommitted the uncommitted event with a persistable event
 * @return the committed event
 * @param <EVENT>
 */
<EVENT extends Event<EVENT>> Event.Committed<EVENT> commitEvent(Event.Uncommitted<EVENT> uncommitted) {
  return switch (uncommitted) {
    case Event.Uncommitted<EVENT>(_, var version) when version == eventStore.size() -> appendEvent(uncommitted);
    default -> throw new IllegalStateException(STR."Can't commit event, event \{uncommitted} not consistent with version in event-store: \{eventStore.size()}");
  };
}

<EVENT extends Event<EVENT>> EVENT emitEvent(Event.Committed<EVENT> committed) {
  System.out.println(STR."Event \{committed} has been committed and emitted");
  return committed.event;
}

@SuppressWarnings("unchecked")
<EVENT extends Event<EVENT>> Event.Uncommitted<EVENT> handleCommand(List<Event.Committed<?>> events, Command<?> command) {
  return switch (command) {
    case Command.BookSeat(var row, var seat, var user)
      when isNot(alreadyBooked(events, row, seat), STR."Can't book seat, command \{command} with already booked seats")
      && isNot(middleSeatMandatory(events, row, seat), STR."Can't book seat, command \{command} must book the middle seat") ->
      new Event.Uncommitted<>((EVENT) new Event.SeatBooked(row, seat, user), events.size());

    default -> throw new IllegalArgumentException("Can't handle command");
  };
}

void main() {
  try (final var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat2, User.Merlin)))));
    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat1, User.Wart)))));
    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat2, User.Merlin)))));

    var task1 = tasks.submit(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat2, User.Wart)))));
    var task2 = tasks.submit(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat2, User.Merlin)))));

    intercept(task1::get);
    intercept(task2::get);

    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat4, User.Wart)))));
  }
}

boolean isNot(boolean condition, String otherwise) {
  if (condition) throw new IllegalArgumentException(otherwise);
  return true;
}

void intercept(Callable<Object> callable) {
  try {
    callable.call();
  } catch (ExecutionException e) {
    System.err.println(STR."\{e.getCause().getMessage()}");
  } catch (Exception e) {
    System.err.println(STR."\{e.getMessage()}");
  }
}
