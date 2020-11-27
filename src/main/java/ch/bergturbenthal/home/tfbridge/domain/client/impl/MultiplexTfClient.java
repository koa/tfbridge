package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.TfClient;
import ch.bergturbenthal.home.tfbridge.domain.device.DeviceHandler;
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

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@RefreshScope
@Service
@Slf4j
public class MultiplexTfClient implements TfClient {
  private final BridgeProperties bridgeProperties;
  private final Map<Integer, DeviceHandler> deviceHandlers;
  private final DiscoveryClient discoveryClient;
  private Map<URI, IPConnection> runningConnections = new ConcurrentHashMap<>();

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

  @Scheduled(fixedDelay = 30 * 1000)
  public void discover() {
    final TFEndpoint tfEndpoint = bridgeProperties.getTfEndpoint();
    final String mqttPrefix = bridgeProperties.getMqtt().getMqttPrefix();
    for (ServiceInstance endpoint : discoveryClient.getInstances(tfEndpoint.getService())) {
      final URI endpointUri = endpoint.getUri();
      final IPConnection runningConnection = runningConnections.get(endpointUri);
      if (runningConnection != null) {
        try {
          runningConnection.enumerate();
          continue;
        } catch (TinkerforgeException ex) {
          log.warn("Error enumerate " + endpointUri.getHost(), ex);
          runningConnections.remove(endpointUri);
        }
      }
      log.info("Connect to " + endpointUri.getHost());
      final IPConnection ipConnection = new IPConnection();
      final Map<String, Disposable> registrations = Collections.synchronizedMap(new HashMap<>());
      AtomicBoolean shuttingDown = new AtomicBoolean(false);
      final Consumer<Throwable> cleanupConsumer =
          ex -> {
            log.error("Error Communicating to " + endpointUri.getHost() + " reset connection");
            final IPConnection connection = runningConnections.remove(endpointUri);
            if (!shuttingDown.getAndSet(true)) registrations.values().forEach(Disposable::dispose);
            if (connection != null) {
              try {
                connection.close();
              } catch (IOException ioException) {
                log.warn("Error on cleanup connection " + endpointUri.getHost(), ioException);
              }
            }
          };

      ipConnection.addEnumerateListener(
          (uid,
              connectedUid,
              position,
              hardwareVersion,
              firmwareVersion,
              deviceIdentifier,
              enumerationType) -> {
            final DeviceHandler foundDeviceHandler = deviceHandlers.get(deviceIdentifier);
            final String hostName = endpointUri.getHost();
            try {
              if (foundDeviceHandler != null) {
                if(registrations.containsKey(uid)){
                  return;
                }
                final Disposable disposable = registrations.remove(uid);
                if (disposable != null) {
                  log.info("Remove old registration on " + uid);
                  disposable.dispose();
                }
                final Disposable overridenDisposable =
                    registrations.put(
                        uid, foundDeviceHandler.registerDevice(uid, ipConnection, cleanupConsumer));

                if (overridenDisposable != null) overridenDisposable.dispose();
                log.info(
                    "Bricklet "
                        + uid
                        + " registered by "
                        + foundDeviceHandler.getClass().getSimpleName()
                        + " (on "
                        + hostName
                        + ")");
              } else log.info("No handler for Bricklet " + uid + " found (on " + hostName + ")");
            } catch (TinkerforgeException e) {
              log.warn("Cannot process registration on " + uid + " via " + hostName, e);
              cleanupConsumer.accept(e);
            }
          });
      ipConnection.addDisconnectedListener(
          disconnectReason -> {
            runningConnections.remove(endpointUri);
            log.info("disconnect " + endpointUri);
            final Iterator<Disposable> iterator = registrations.values().iterator();
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
        ipConnection.setTimeout(2500);
        ipConnection.connect(hostName, port);
      } catch (TinkerforgeException ex) {
        // log.warn("Cannot connect to " + hostName + ":" + port, ex);
      }
    }
    discoveryClient.getInstances(tfEndpoint.getService()).stream()
        .map(ServiceInstance::getUri)
        .filter(u -> !runningConnections.containsKey(u))
        .forEach(u -> log.info("Missing connection to " + u));
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
