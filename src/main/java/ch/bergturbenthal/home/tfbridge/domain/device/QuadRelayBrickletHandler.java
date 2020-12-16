package ch.bergturbenthal.home.tfbridge.domain.device;

import com.tinkerforge.BrickletIndustrialQuadRelayV2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.function.Consumer;

@Service
@Slf4j
public class QuadRelayBrickletHandler implements DeviceHandler {
  @Override
  public int deviceId() {
    return BrickletIndustrialQuadRelayV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          final String uid, final IPConnection connection, final Consumer<Throwable> errorConsumer)
          throws TinkerforgeException {
    final BrickletIndustrialQuadRelayV2 bricklet =
            new BrickletIndustrialQuadRelayV2(uid, connection);
    // bricklet.setValue();

    return () -> {
    };
  }
}
