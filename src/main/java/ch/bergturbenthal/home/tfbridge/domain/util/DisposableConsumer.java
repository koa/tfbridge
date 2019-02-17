package ch.bergturbenthal.home.tfbridge.domain.util;

import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DisposableConsumer implements Consumer<Disposable> {
  final AtomicReference<Disposable> currentValue = new AtomicReference<>();

  @Override
  public void accept(final Disposable disposable) {
    final Disposable oldValue = currentValue.getAndSet(disposable);
    if (oldValue != null) oldValue.dispose();
  }
}
