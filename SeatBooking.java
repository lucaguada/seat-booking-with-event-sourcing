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

sealed interface Event<EVENT extends Event<EVENT>> {
  record SeatBooked(Row row, Seat seat) implements Event<SeatBooked> {}

  record Uncommitted<EVENT extends Event<EVENT>>(EVENT event, int version) {}

  record Committed<EVENT extends Event<EVENT>>(UUID id, EVENT event, int version, LocalDateTime storedAt) implements Event<SeatBooked> {
    Committed(EVENT event, int version) {
      this(UUID.randomUUID(), event, version, LocalDateTime.now());
    }
  }
}

sealed interface Command<COMMAND extends Command<COMMAND>> {
  record BookSeat(Row row, Seat seat) implements Command<BookSeat> {}
}

/**
 * event-store
 */
final Queue<Event.Committed<?>> eventStore = new ArrayDeque<>();

/**
 * load current events list
 * @return current events list
 */
List<Event.Committed<?>> loadEvents() {return eventStore.stream().toList();}

/**
 * append an event to the event-store
 * @param event event to append
 * @return appended event
 */
<EVENT extends Event<EVENT>> Event.Committed<EVENT> appendEvent(Event.Uncommitted<EVENT> event) {
  Event.Committed<EVENT> committed = new Event.Committed<>(event.event, event.version);
  if (eventStore.size() == committed.version)
    eventStore.add(committed);
  else
    throw new IllegalStateException(STR."Can't append uncommitted event, event \{event} not consistent with committed-events version \{eventStore.size()}");
  return committed;
}

boolean alreadyBooked(List<Event.Committed<?>> events, Row brow, Seat bseat) {
  return events.stream().anyMatch(stored -> switch (stored.event) {
    case Event.SeatBooked(var row, var seat) -> row == brow && seat == bseat;
    default -> false;
  });
}

boolean middleSeatIsOptional(List<Event.Committed<?>> events, Row row, Seat seat) {
  if (seat == Seat.Seat1 || seat == Seat.Seat5) return true;
  return events.stream()
    .filter(committed -> committed.event instanceof Event.SeatBooked(var row1, _) && row1 == row)
    .noneMatch(committed -> committed.event instanceof Event.SeatBooked(_, var seat1) && (seat1 == Seat.Seat2 || seat1 == Seat.Seat3 || seat1 == Seat.Seat4));
}

<EVENT extends Event<EVENT>> Event.Committed<EVENT> commitEvent(Event.Uncommitted<EVENT> uncommitted) {
  return switch (uncommitted) {
    case Event.Uncommitted<EVENT>(_, var version) when version == eventStore.size() -> appendEvent(uncommitted);
    default -> throw new IllegalStateException(STR."Can't commit event, event \{uncommitted} with version \{uncommitted.version} not consistent with uncommitted-events version \{eventStore.size()}");
  };
}

<EVENT extends Event<EVENT>> EVENT emitEvent(Event.Committed<EVENT> committed) {
  System.out.println(STR."Event \{committed} has been committed and emitted");
  return committed.event;
}

@SuppressWarnings("unchecked")
<EVENT extends Event<EVENT>> Event.Uncommitted<EVENT> handleCommand(List<Event.Committed<?>> events, Command<?> command) {
  return switch (command) {
    case Command.BookSeat(var row, var seat)
      when
      claim(!alreadyBooked(events, row, seat), STR."Can't book seat, command \{command} with already booked seats") &&
        claim(middleSeatIsOptional(events, row, seat), STR."Can't book seat, command \{command} must book the middle seat") -> new Event.Uncommitted<>((EVENT) new Event.SeatBooked(row, seat), events.size());

    default -> throw new IllegalArgumentException("Can't handle command");
  };
}

void main() {
  try (final var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat2)))));
    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat1)))));
    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat2)))));

    var task1 = tasks.submit(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat2)))));
    var task2 = tasks.submit(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat2)))));

    intercept(task1::get);
    intercept(task2::get);

    intercept(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat4)))));
  }
}

boolean claim(boolean condition, String otherwise) {
  if (condition) return true;
  throw new IllegalArgumentException(otherwise);
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
