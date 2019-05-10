package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.TfClient;
import ch.bergturbenthal.home.tfbridge.domain.device.DeviceHandler;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.TFEndpoint;
import com.tinkerforge.BrickMaster;
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

import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@RefreshScope
@Service
@Slf4j
public class MultiplexTfClient implements TfClient {
  private final BridgeProperties            bridgeProperties;
  private final Map<Integer, DeviceHandler> deviceHandlers;
  private final DiscoveryClient             discoveryClient;
  private       Map<URI, IPConnection>      runningConnections = new ConcurrentHashMap<>();

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
      if (runningConnections.containsKey(endpointUri)) continue;
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
                try {
                  if (foundDeviceHandler != null) {
                    registrations.add(foundDeviceHandler.registerDevice(uid, ipConnection));
                    log.info(
                            "Bricklet "
                                    + uid
                                    + " registered by "
                                    + foundDeviceHandler.getClass().getSimpleName());
                  } else log.info("No handler for Bricklet " + uid + " found");
                } catch (TinkerforgeException e) {
                  log.warn(
                          "Cannot process registration on " + uid + " via " + endpointUri.getHost(), e);
                }
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
                  runningConnections.put(endpointUri, ipConnection);
                } catch (NotConnectedException e) {
                  log.warn("Cannot connect", e);
                }
              });
      final String hostName = endpoint.getHost();
      final int port = endpoint.getPort();
      try {
        ipConnection.setAutoReconnect(true);
        ipConnection.setTimeout(10);
        ipConnection.connect(hostName, port);
      } catch (TinkerforgeException ex) {
        log.warn("Cannot connect to " + hostName + ":" + port, ex);
      }
    }
  }

  @PreDestroy
  public void disconnectAll() {
    runningConnections
            .values()
            .forEach(
                    c -> {
                      try {
                        Semaphore waitSemaphore = new Semaphore(0);
                        c.addDisconnectedListener(
                                (reason) -> {
                                  waitSemaphore.release();
                                });
                        c.disconnect();
                        waitSemaphore.tryAcquire(5, TimeUnit.SECONDS);
                      } catch (NotConnectedException | InterruptedException e) {
                        log.warn("Cannot disconnect", e);
                      }
                    });
  }
}
