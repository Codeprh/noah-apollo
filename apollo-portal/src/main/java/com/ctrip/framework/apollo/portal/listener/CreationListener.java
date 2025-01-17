package com.ctrip.framework.apollo.portal.listener;

import com.ctrip.framework.apollo.common.dto.AppDTO;
import com.ctrip.framework.apollo.common.dto.AppNamespaceDTO;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.portal.api.AdminServiceAPI;
import com.ctrip.framework.apollo.portal.component.PortalSettings;
import com.ctrip.framework.apollo.tracer.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 创建事件监听器
 */
@Component
public class CreationListener {

    private static Logger logger = LoggerFactory.getLogger(CreationListener.class);

    private final PortalSettings portalSettings;
    private final AdminServiceAPI.AppAPI appAPI;
    private final AdminServiceAPI.NamespaceAPI namespaceAPI;

    public CreationListener(
            final PortalSettings portalSettings,
            final AdminServiceAPI.AppAPI appAPI,
            final AdminServiceAPI.NamespaceAPI namespaceAPI) {
        this.portalSettings = portalSettings;
        this.appAPI = appAPI;
        this.namespaceAPI = namespaceAPI;
    }

    /**
     * app创建事件监听器：
     * 1、spring处理瞬时事件
     * 2、早期实现：实现ApplicationListener接口并覆写onApplicationEvent方法
     * 3、新实现：@EventListener
     *
     * @param event
     */
    @EventListener
    public void onAppCreationEvent(AppCreationEvent event) {
        AppDTO appDTO = BeanUtils.transform(AppDTO.class, event.getApp());
        List<Env> envs = portalSettings.getActiveEnvs();
        for (Env env : envs) {
            try {
                appAPI.createApp(env, appDTO);
            } catch (Throwable e) {
                logger.error("Create app failed. appId = {}, env = {})", appDTO.getAppId(), env, e);
                Tracer.logError(String.format("Create app failed. appId = %s, env = %s", appDTO.getAppId(), env), e);
            }
        }
    }

    @EventListener
    public void onAppNamespaceCreationEvent(AppNamespaceCreationEvent event) {
        AppNamespaceDTO appNamespace = BeanUtils.transform(AppNamespaceDTO.class, event.getAppNamespace());
        List<Env> envs = portalSettings.getActiveEnvs();
        for (Env env : envs) {
            try {
                namespaceAPI.createAppNamespace(env, appNamespace);
            } catch (Throwable e) {
                logger.error("Create appNamespace failed. appId = {}, env = {}", appNamespace.getAppId(), env, e);
                Tracer.logError(String.format("Create appNamespace failed. appId = %s, env = %s", appNamespace.getAppId(), env), e);
            }
        }
    }

}
