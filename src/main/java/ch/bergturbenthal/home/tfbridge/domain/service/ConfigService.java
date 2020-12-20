package ch.bergturbenthal.home.tfbridge.domain.service;

import ch.bergturbenthal.home.tfbridge.domain.ha.PublishingConfig;

public interface ConfigService {
  <P extends PublishingConfig> void publishConfig(final P config);

  void unpublishConfig(String id);

  default void unpublishConfig(PublishingConfig config) {
    unpublishConfig(config.id());
  }

  <P extends PublishingConfig> void registerForConfiguration(
          Class<P> configType, String id, ConfigurationListener<P> listener);

  interface ConfigurationListener<P extends PublishingConfig> {
    void notifyConfigAdded(P configuration);

    void notifyConfigRemoved(P configuration);
  }
}
