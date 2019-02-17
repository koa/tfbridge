package ch.bergturbenthal.home.tfbridge.domain.device;

import com.tinkerforge.IPConnection;
import reactor.core.Disposable;

public interface DeviceHandler {
  int deviceId();

  Disposable registerDevice(String uid, String name, IPConnection connection);
}
