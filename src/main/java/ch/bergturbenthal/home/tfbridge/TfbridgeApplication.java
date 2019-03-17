package ch.bergturbenthal.home.tfbridge;

import ch.bergturbenthal.home.tfbridge.domain.client.TfClient;
import ch.bergturbenthal.home.tfbridge.domain.client.impl.MultiplexTfClient;
import ch.bergturbenthal.home.tfbridge.domain.device.DeviceHandler;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.time.Duration;

@EnableConfigurationProperties
@SpringBootApplication
@ComponentScan(basePackageClasses = {BridgeProperties.class, MultiplexTfClient.class, DeviceHandler.class})
@Import(SimpleDiscoveryClientAutoConfiguration.class)
public class TfbridgeApplication {

  public static void main(String[] args) throws InterruptedException {
    final ConfigurableApplicationContext run =
            SpringApplication.run(TfbridgeApplication.class, args);
    final TfClient bean = run.getBean(TfClient.class);
    System.out.println(bean);
    Thread.sleep(Duration.ofMinutes(20).toMillis());
  }
}
