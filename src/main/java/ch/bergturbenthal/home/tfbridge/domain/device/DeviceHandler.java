package ch.bergturbenthal.home.tfbridge.domain.device;

import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import reactor.core.Disposable;

public interface DeviceHandler {
  int deviceId();

  Disposable registerDevice(String uid, IPConnection connection)
      throws TinkerforgeException;
}
