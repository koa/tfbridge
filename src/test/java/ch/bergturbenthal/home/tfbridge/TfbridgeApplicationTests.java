package ch.bergturbenthal.home.tfbridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(properties = {"spring.cloud.kubernetes.discovery.enabled=false"})
public class TfbridgeApplicationTests {

  @Test
  public void contextLoads() {
  }

}

