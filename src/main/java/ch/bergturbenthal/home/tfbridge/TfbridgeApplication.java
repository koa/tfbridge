package ch.bergturbenthal.home.tfbridge;

import ch.bergturbenthal.home.tfbridge.domain.client.impl.MultiplexTfClient;
import ch.bergturbenthal.home.tfbridge.domain.device.DeviceHandler;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.service.DefaultConfigPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@EnableConfigurationProperties
@SpringBootApplication(proxyBeanMethods = false)
@ComponentScan(
        basePackageClasses = {
                BridgeProperties.class,
                MultiplexTfClient.class,
                DeviceHandler.class,
                DefaultConfigPublisher.class
        })
@Import(SimpleDiscoveryClientAutoConfiguration.class)
@EnableScheduling
@Slf4j
public class TfbridgeApplication {

  public static void main(String[] args) throws InterruptedException {
    final ConfigurableApplicationContext run =
            SpringApplication.run(TfbridgeApplication.class, args);

    /*log.info("Shutdown application " + run.isActive());
    Thread.getAllStackTraces()
        .forEach(
            ((thread, stackTraceElements) -> {
              log.info("Thread: " + thread.getName() + " state: " + thread.getState());
              Arrays.stream(stackTraceElements)
                  .forEach(
                      stackTraceElement -> {
                        log.info(
                            "  "
                                + stackTraceElement.getClassName()
                                + ";"
                                + stackTraceElement.getMethodName()
                                + ", "
                                + stackTraceElement.getFileName()
                                + ":"
                                + stackTraceElement.getLineNumber());
                      });
            }));
     */
  }

  @Bean
  public ScheduledExecutorService scheduledExecutorService() {
    return Executors.newScheduledThreadPool(2);
  }
}
