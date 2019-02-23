package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import com.tinkerforge.IPConnection;
import reactor.core.Disposable;

public interface DeviceHandler {
  int deviceId();

  Disposable registerDevice(String uid, BrickletSettings settings, IPConnection connection);
}
