package ch.bergturbenthal.home.tfbridge.domain.device;

import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import reactor.core.Disposable;

import java.util.function.Consumer;

public interface DeviceHandler {
  int deviceId();

  Disposable registerDevice(String uid,
                            IPConnection connection,
                            final Consumer<Throwable> errorConsumer)
      throws TinkerforgeException;
}
