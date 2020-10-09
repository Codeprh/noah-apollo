package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigFile;
import com.ctrip.framework.apollo.core.enums.ConfigFileFormat;

/**
 * 配置管理器接口。提供获得 Config 和 ConfigFile 对象的接口
 * @author Jason Song(song_s@ctrip.com)
 */
public interface ConfigManager {
  /**
   * Get the config instance for the namespace specified.
   * @param namespace the namespace
   * @return the config instance for the namespace
   */
  public Config getConfig(String namespace);

  /**
   * Get the config file instance for the namespace specified.
   * @param namespace the namespace
   * @param configFileFormat the config file format
   * @return the config file instance for the namespace
   */
  public ConfigFile getConfigFile(String namespace, ConfigFileFormat configFileFormat);
}
