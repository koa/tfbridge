package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

import java.util.List;

@ConstructorBinding
@Value
public class WarmColdDmxLight {
  String       id;
  String       name;
  String       dmxBricklet;
  int          warmAddress;
  int          coldAddress;
  int          warmTemperature;
  int          coldTemperature;
  List<String> triggers;
  List<String> motionDetectors;
}
