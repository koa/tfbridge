package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

import java.util.List;

@ConstructorBinding
@Value
public class SimpleDmxLight {
  String       id;
  String       name;
  String       dmxBricklet;
  int          address;
  List<String> triggers;
  List<String> motionDetectors;
}
