package ch.bergturbenthal.home.tfbridge.domain.device;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class SinglePendingSchedulerConsumer implements Consumer<ScheduledFuture<?>> {
  private final AtomicReference<ScheduledFuture<?>> currentState = new AtomicReference<>();

  @Override
  public void accept(final ScheduledFuture<?> scheduledFuture) {
    final ScheduledFuture<?> oldSchedule = currentState.getAndSet(scheduledFuture);
    if (oldSchedule != null && !oldSchedule.isDone()) oldSchedule.cancel(false);
  }
}
