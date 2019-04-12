package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.TfClient;
import ch.bergturbenthal.home.tfbridge.domain.device.DeviceHandler;
import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.TFEndpoint;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import java.util.stream.Collectors;

@RefreshScope
@Service
@Slf4j
public class MultiplexTfClient implements TfClient {
  private final BridgeProperties            bridgeProperties;
  private final Map<Integer, DeviceHandler> deviceHandlers;
  private final DiscoveryClient             discoveryClient;
  private       Set<URI>                    runningConnections = new CopyOnWriteArraySet<>();

  public MultiplexTfClient(
          final BridgeProperties bridgeProperties,
          final List<DeviceHandler> deviceHandlerList,
          DiscoveryClient discoveryClient) {
    this.bridgeProperties = bridgeProperties;
    deviceHandlers =
            deviceHandlerList.stream()
                             .collect(Collectors.toMap(DeviceHandler::deviceId, Function.identity()));

    this.discoveryClient = discoveryClient;
    discover();
  }

  @Scheduled(fixedDelay = 60 * 1000)
  public void discover() {
    final TFEndpoint tfEndpoint = bridgeProperties.getTfEndpoint();
    for (ServiceInstance endpoint : discoveryClient.getInstances(tfEndpoint.getService())) {
      final URI endpointUri = endpoint.getUri();
      if (runningConnections.contains(endpointUri)) continue;
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
                final DeviceHandler foundDeviceHandler = deviceHandlers.get(deviceIdentifier);
                if (foundDeviceHandler != null) {
                  final BrickletSettings settings = getBrickletSettings(bridgeProperties, uid);
                  registrations.add(foundDeviceHandler.registerDevice(uid, settings, ipConnection));
                  log.info("Bricklet " + uid + " registered by " + foundDeviceHandler.getClass()
                                                                                     .getSimpleName() + ": " + settings);
                } else
                  log.info("No handler for Bricklet " + uid + " found");
              });
      ipConnection.addDisconnectedListener(
              disconnectReason -> {
                runningConnections.remove(endpointUri);
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
                  runningConnections.add(endpointUri);
                } catch (NotConnectedException e) {
                  log.warn("Cannot connect", e);
                }
              });
      final String hostName = endpoint.getHost();
      final int port = endpoint.getPort();
      try {
        ipConnection.connect(hostName, port);
        ipConnection.setAutoReconnect(true);
      } catch (TinkerforgeException ex) {
        log.warn("Cannot connect to " + hostName + ":" + port, ex);
      }
    }
  }

  public BrickletSettings getBrickletSettings(
          final BridgeProperties bridgeProperties, final String uid) {
    final BrickletSettings brickletSettings = bridgeProperties.getBricklets().get(uid);
    if (brickletSettings != null) {
      return brickletSettings;
    }
    final BrickletSettings emptySettings = new BrickletSettings();
    emptySettings.setName(uid);
    return emptySettings;
  }
}
