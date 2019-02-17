package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.TfClient;
import ch.bergturbenthal.home.tfbridge.domain.device.DeviceHandler;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.TFEndpoint;
import com.tinkerforge.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RefreshScope
@Service
@Slf4j
public class MultiplexTfClient implements TfClient {
  private final BridgeProperties bridgeProperties;

  public MultiplexTfClient(
          final BridgeProperties bridgeProperties, final List<DeviceHandler> deviceHandlerList)
          throws NetworkException, AlreadyConnectedException {
    this.bridgeProperties = bridgeProperties;
    Map<Integer, DeviceHandler> deviceHandlers =
            deviceHandlerList
                    .stream()
                    .collect(Collectors.toMap(DeviceHandler::deviceId, Function.identity()));

    for (TFEndpoint endpoint : bridgeProperties.getTfEndpoint()) {
      final IPConnection ipConnection = new IPConnection();
      final List<Disposable> registrations = Collections.synchronizedList(new ArrayList<>());
      ipConnection.addEnumerateListener(
              (uid,
               connectedUid,
               position,
               hardwareVersion,
               firmwareVersion,
               deviceIdentifier,
               enumerationType) -> {
                final String deviceName = bridgeProperties.getBricklets().getOrDefault(uid, uid);
                final DeviceHandler foundDeviceHandler = deviceHandlers.get(deviceIdentifier);
                if (foundDeviceHandler != null) {
                  registrations.add(foundDeviceHandler.registerDevice(uid, deviceName, ipConnection));
                }
              });
      ipConnection.addDisconnectedListener(
              disconnectReason -> {
                log.info("disconnect " + endpoint.toString());
                final Iterator<Disposable> iterator = registrations.iterator();
                while (iterator.hasNext()) {
                  final Disposable next = iterator.next();
                  next.dispose();
                  iterator.remove();
                }
              });
      ipConnection.addConnectedListener(
              connectReason -> {
                try {
                  ipConnection.enumerate();
                } catch (NotConnectedException e) {
                  log.warn("Cannot connect", e);
                }
              });
      final String hostName = endpoint.getHost().getHostName();
      final int port = endpoint.getPort();
      try {
        ipConnection.connect(hostName, port);
        ipConnection.setAutoReconnect(true);
      } catch (TinkerforgeException ex) {
        log.warn("Cannot connect to " + hostName + ":" + port, ex);
      }
    }
  }
}
