package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class Device {
  List<String> identifiers;
  List<List<String>> connections;
  String model;
  String manufacturer;
  String sw_version;
  String name;
}
