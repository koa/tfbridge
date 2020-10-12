package ch.bergturbenthal.home.tfbridge.domain.util;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import com.tinkerforge.Device;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.regex.Pattern;

@Slf4j
public class MqttMessageUtil {
  public static final MqttMessage ONLINE_MESSAGE = MqttMessageUtil.createMessage("online", true);
  public static final MqttMessage OFFLINE_MESSAGE = MqttMessageUtil.createMessage("offline", true);
  private static final Pattern NON_ASCII_PATTERN = Pattern.compile("[^A-Za-z0-9]");

  public static MqttMessage createMessage(final String content, final boolean retained) {
    final MqttMessage message = new MqttMessage();
    message.setRetained(retained);
    message.setQos(1);
    message.setPayload(content.getBytes());
    return message;
  }

  public static MqttMessage createVersionMessage(final short[] version) {

    return createMessage(version[0] + "." + version[1] + "." + version[2], true);
  }

  public static void publishVersions(
      final MqttClient mqttClient, final String channelPrefix, final Device.Identity identity) {
    mqttClient.send(
        channelPrefix + "/hardwareVersion",
        MqttMessageUtil.createVersionMessage(identity.hardwareVersion));
    mqttClient.send(
        channelPrefix + "/firmwareVersion",
        MqttMessageUtil.createVersionMessage(identity.firmwareVersion));
  }

  public static String strip(final String s) {
    return NON_ASCII_PATTERN.matcher(s).replaceAll("_");
  }
}
