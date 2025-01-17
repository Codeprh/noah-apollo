package com.ctrip.framework.apollo.biz.utils;


import com.google.common.base.Joiner;

import com.ctrip.framework.apollo.core.ConfigConsts;

public class ReleaseMessageKeyGenerator {

  private static final Joiner STRING_JOINER = Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR);

  public static String generate(String appId, String cluster, String namespace) {
    //把三个变量join一起，用指定的符号
    return STRING_JOINER.join(appId, cluster, namespace);
  }
}
