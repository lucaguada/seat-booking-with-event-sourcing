import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
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

int versionOf(List<Event.Stored<?>> events) {return events.size();}

<EVENT extends Event<EVENT>> Event.Stored<EVENT> commitEvent(EVENT event) {
  return switch (new Event.Stored<EVENT>(event, versionOf(loadEvents()))) {
    case Event.Stored<EVENT> uncommitted when uncommitted.version == events.size() -> {
      events.add(uncommitted);
      yield uncommitted;
    }
    default -> throw new IllegalStateException(STR."Can't commit event, event not consistent with version \{event}");
  };
}

<EVENT extends Event<EVENT>> void emitEvent(Event.Stored<EVENT> committed) {
  System.out.println(STR."Event \{committed} has been committed and emitted");
}

@SuppressWarnings("unchecked")
<EVENT extends Event<EVENT>> EVENT handleCommand(List<Event.Stored<?>> events, Command<?> command) {
  return switch (command) {
    case Command.BookSeat(var row, var seat) when !alreadyBooked(events, row, seat) -> (EVENT) new Event.SeatBooked(row, seat);
    default -> throw new IllegalArgumentException(STR."Can't book seat, command \{command} has already booked seats");
  };
}

void main() {
  emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat2))));
  emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat1))));

  try (final var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
    var task1 = tasks.submit(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat2)))));
    var task2 = tasks.submit(() -> emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row2, Seat.Seat2)))));

    task1.get();
    task2.get();
  } catch (ExecutionException | InterruptedException e) {
    System.err.println(STR."\{e.getMessage()}");
  }

  emitEvent(commitEvent(this.<Event.SeatBooked>handleCommand(loadEvents(), new Command.BookSeat(Row.Row1, Seat.Seat1))));

}
