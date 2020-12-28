package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@Value
public class Bell {
  String id;
  String keyId;
  String name;
  String bricklet;
  int    address;
}
