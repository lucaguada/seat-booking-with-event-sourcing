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

  record Stored<EVENT extends Event<EVENT>>(UUID id, EVENT event, int version, LocalDateTime storedAt) implements Event<SeatBooked> {
    Stored(EVENT event, int version) {
      this(UUID.randomUUID(), event, version, LocalDateTime.now());
    }
  }
}

sealed interface Command<COMMAND extends Command<COMMAND>> {
  record BookSeat(Row row, Seat seat) implements Command<BookSeat> {}
}

final Queue<Event.Stored<?>> events = new ArrayDeque<>();

List<Event.Stored<?>> loadEvents() {return events.stream().toList();}

boolean alreadyBooked(List<Event.Stored<?>> events, Row brow, Seat bseat) {
  return events.stream().anyMatch(stored -> switch (stored.event) {
    case Event.SeatBooked(var row, var seat) -> row == brow && seat == bseat;
    default -> false;
  });
}

boolean middleSeatIsOptional(List<Event.Stored<?>> events, Row brow) {
  return events.stream()
    .filter(stored -> stored.event instanceof Event.SeatBooked(var row, _) && row == brow)
    .noneMatch(stored -> stored.event instanceof Event.SeatBooked(_, var seat) && (seat == Seat.Seat2 || seat == Seat.Seat3 || seat == Seat.Seat4));
}

int versionOf(List<Event.Stored<?>> events) {return events.size();}

<EVENT extends Event<EVENT>> Event.Stored<EVENT> commitEvent(EVENT event) {
  final int version = versionOf(loadEvents());
  return switch (new Event.Stored<EVENT>(event, version)) {
    case Event.Stored<EVENT> uncommitted when uncommitted.version == events.size() -> {
      events.add(uncommitted);
      yield uncommitted;
    }
    default -> throw new IllegalStateException(STR."Can't commit event, event \{event} with version \{version} not consistent with stored-events version \{events.size()}");
  };
}

<EVENT extends Event<EVENT>> EVENT emitEvent(Event.Stored<EVENT> committed) {
  System.out.println(STR."Event \{committed} has been committed and emitted");
  return committed.event;
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

@SuppressWarnings("unchecked")
<EVENT extends Event<EVENT>> EVENT handleCommand(List<Event.Stored<?>> events, Command<?> command) {
  return switch (command) {
    case Command.BookSeat(var row, var seat)
      when
      claim(!alreadyBooked(events, row, seat), STR."Can't book seat, command \{command} with already booked seats") &&
      claim(middleSeatIsOptional(events, row, seat), STR."Can't book seat, command \{command} must book the middle seat") -> (EVENT) new Event.SeatBooked(row, seat);

    default -> throw new IllegalArgumentException("Can't handle command");
  };
}

boolean middleSeatIsOptional(List<Event.Stored<?>> events, Row row, Seat seat) {
  return seat == Seat.Seat1 || seat == Seat.Seat5 || middleSeatIsOptional(events, row);
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
