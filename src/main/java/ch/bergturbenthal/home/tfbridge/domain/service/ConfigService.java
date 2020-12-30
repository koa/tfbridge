package ch.bergturbenthal.home.tfbridge.domain.service;

import ch.bergturbenthal.home.tfbridge.domain.ha.HasDevice;
import ch.bergturbenthal.home.tfbridge.domain.ha.PublishingConfig;

public interface ConfigService {
  <P extends PublishingConfig> void publishConfig(final P config);

  void unpublishConfig(String id);

  default void unpublishConfig(PublishingConfig config) {
    unpublishConfig(config.id());
  }

  <P extends PublishingConfig> void registerForConfiguration(
          Class<P> configType, String id, ConfigurationListener<P> listener);

  <P extends PublishingConfig & HasDevice> void registerForDeviceAndConfiguration(
          Class<P> configType, String deviceId, ConfigurationListener<P> listener);

  interface ConfigurationListener<P extends PublishingConfig> {
    void notifyConfigAdded(P configuration);

    void notifyConfigRemoved(P configuration);
  }
}
