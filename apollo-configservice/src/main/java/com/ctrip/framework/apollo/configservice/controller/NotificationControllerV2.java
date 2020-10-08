package com.ctrip.framework.apollo.configservice.controller;

import com.ctrip.framework.apollo.biz.config.BizConfig;
import com.ctrip.framework.apollo.biz.entity.ReleaseMessage;
import com.ctrip.framework.apollo.biz.message.ReleaseMessageListener;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.utils.EntityManagerUtil;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.configservice.service.ReleaseMessageServiceWithCache;
import com.ctrip.framework.apollo.configservice.util.NamespaceUtil;
import com.ctrip.framework.apollo.configservice.util.WatchKeysUtil;
import com.ctrip.framework.apollo.configservice.wrapper.DeferredResultWrapper;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.dto.ApolloConfigNotification;
import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Jason Song(song_s@ctrip.com)
 */
@RestController
@RequestMapping("/notifications/v2")
public class NotificationControllerV2 implements ReleaseMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(NotificationControllerV2.class);
    /**
     * 通知编号 = ReleaseMessage.id
     * Watch Key = ReleaseMessage.message
     * <p>
     * Watch Key 与 DeferredResultWrapper 的 Multimap
     * <p>
     * Key：Watch Key
     * Value：DeferredResultWrapper 数组
     */
    private final Multimap<String, DeferredResultWrapper> deferredResults =
            Multimaps.synchronizedSetMultimap(HashMultimap.create());

    private static final Splitter STRING_SPLITTER =
            Splitter.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR).omitEmptyStrings();

    private static final Type notificationsTypeReference =
            new TypeToken<List<ApolloConfigNotification>>() {
            }.getType();

    /**
     * 大量通知分批执行 ExecutorService
     */
    private final ExecutorService largeNotificationBatchExecutorService;

    private final WatchKeysUtil watchKeysUtil;
    private final ReleaseMessageServiceWithCache releaseMessageService;
    private final EntityManagerUtil entityManagerUtil;
    private final NamespaceUtil namespaceUtil;
    private final Gson gson;
    private final BizConfig bizConfig;

    @Autowired
    public NotificationControllerV2(
            final WatchKeysUtil watchKeysUtil,
            final ReleaseMessageServiceWithCache releaseMessageService,
            final EntityManagerUtil entityManagerUtil,
            final NamespaceUtil namespaceUtil,
            final Gson gson,
            final BizConfig bizConfig) {
        largeNotificationBatchExecutorService = Executors.newSingleThreadExecutor(ApolloThreadFactory.create
                ("NotificationControllerV2", true));
        this.watchKeysUtil = watchKeysUtil;
        this.releaseMessageService = releaseMessageService;
        this.entityManagerUtil = entityManagerUtil;
        this.namespaceUtil = namespaceUtil;
        this.gson = gson;
        this.bizConfig = bizConfig;
    }

    /**
     * DeferredResult设计：
     * FROM 《Apollo配置中心设计》 的 2.1.2 Config Service 通知客户端的实现方式
     * <p>
     * 客户端会发起一个Http 请求到 Config Service 的 notifications/v2 接口，也就是NotificationControllerV2 ，参见 RemoteConfigLongPollService 。
     * <p>
     * NotificationControllerV2 不会立即返回结果，而是通过 Spring DeferredResult 把请求挂起。
     * 如果在 60 秒内没有该客户端关心的配置发布，那么会返回 Http 状态码 304 给客户端。
     * <p>
     * 如果有该客户端关心的配置发布，NotificationControllerV2 会调用 DeferredResult 的 setResult 方法，传入有配置变化的 namespace 信息，同时该请求会立即返回。
     * 客户端从返回的结果中获取到配置变化的 namespace 后，会立即请求 Config Service 获取该 namespace 的最新配置。
     *
     * @param appId
     * @param cluster
     * @param notificationsAsString
     * @param dataCenter
     * @param clientIp
     * @return
     */
    @GetMapping
    public DeferredResult<ResponseEntity<List<ApolloConfigNotification>>> pollNotification(
            @RequestParam(value = "appId") String appId,
            @RequestParam(value = "cluster") String cluster,
            @RequestParam(value = "notifications") String notificationsAsString,
            @RequestParam(value = "dataCenter", required = false) String dataCenter,
            @RequestParam(value = "ip", required = false) String clientIp) {

        // 解析 notificationsAsString 参数，创建 ApolloConfigNotification 数组。
        List<ApolloConfigNotification> notifications = null;

        try {
            notifications =
                    gson.fromJson(notificationsAsString, notificationsTypeReference);
        } catch (Throwable ex) {
            Tracer.logError(ex);
        }

        if (CollectionUtils.isEmpty(notifications)) {
            throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
        }

        // 创建 DeferredResultWrapper 对象，读取默认无响应超时时间60s
        DeferredResultWrapper deferredResultWrapper = new DeferredResultWrapper(bizConfig.longPollingTimeoutInMilli());

        Set<String> namespaces = Sets.newHashSet();

        //客户端的通知 Map 。key 为 Namespace 名，value 为通知编号。
        Map<String, Long> clientSideNotifications = Maps.newHashMap();

        //过滤并创建 ApolloConfigNotification Map（统一格式化）
        Map<String, ApolloConfigNotification> filteredNotifications = filterNotifications(appId, notifications);

        //循环 ApolloConfigNotification Map ，初始化上述变量。
        for (Map.Entry<String, ApolloConfigNotification> notificationEntry : filteredNotifications.entrySet()) {

            String normalizedNamespace = notificationEntry.getKey();
            ApolloConfigNotification notification = notificationEntry.getValue();

            namespaces.add(normalizedNamespace);
            clientSideNotifications.put(normalizedNamespace, notification.getNotificationId());

            if (!Objects.equals(notification.getNamespaceName(), normalizedNamespace)) {
                deferredResultWrapper.recordNamespaceNameNormalizedResult(notification.getNamespaceName(), normalizedNamespace);
            }
        }

        if (CollectionUtils.isEmpty(namespaces)) {
            throw new BadRequestException("Invalid format of notifications: " + notificationsAsString);
        }

        Multimap<String, String> watchedKeysMap =
                watchKeysUtil.assembleAllWatchKeys(appId, cluster, namespaces, dataCenter);

        Set<String> watchedKeys = Sets.newHashSet(watchedKeysMap.values());

        /**
         * 1、set deferredResult before the check, for avoid more waiting
         * If the check before setting deferredResult,it may receive a notification the next time
         * when method handleMessage is executed between check and set deferredResult.
         */
        //注册超时事件
        deferredResultWrapper
                .onTimeout(() -> logWatchedKeys(watchedKeys, "Apollo.LongPoll.TimeOutKeys"));

        //注册结束事件
        deferredResultWrapper.onCompletion(() -> {
            //unregister all keys
            for (String key : watchedKeys) {
                deferredResults.remove(key, deferredResultWrapper);
            }
            logWatchedKeys(watchedKeys, "Apollo.LongPoll.CompletedKeys");
        });

        //注册 Watch Key + DeferredResultWrapper 到 `deferredResults` 中，等待配置发生变化后通知。详见 `#handleMessage(...)` 方法。
        //register all keys
        for (String key : watchedKeys) {
            this.deferredResults.put(key, deferredResultWrapper);
        }

        logWatchedKeys(watchedKeys, "Apollo.LongPoll.RegisteredKeys");
        logger.debug("Listening {} from appId: {}, cluster: {}, namespace: {}, datacenter: {}",
                watchedKeys, appId, cluster, namespaces, dataCenter);

        /**
         * 2、check new release
         */
        List<ReleaseMessage> latestReleaseMessages =
                releaseMessageService.findLatestReleaseMessagesGroupByMessages(watchedKeys);

        /**
         * Manually close the entity manager.
         * Since for async request, Spring won't do so until the request is finished,
         * which is unacceptable since we are doing long polling - means the db connection would be hold
         * for a very long time
         */
        // 手动关闭 EntityManager
        // 因为对于 async 请求，Spring 在请求完成之前不会这样做
        // 这是不可接受的，因为我们正在做长轮询——意味着 db 连接将被保留很长时间。
        // 实际上，下面的过程，我们已经不需要 db 连接，因此进行关闭。
        entityManagerUtil.closeEntityManager();

        List<ApolloConfigNotification> newNotifications =
                getApolloConfigNotifications(namespaces, clientSideNotifications, watchedKeysMap,
                        latestReleaseMessages);

        //若有新的通知，直接设置结果
        if (!CollectionUtils.isEmpty(newNotifications)) {
            deferredResultWrapper.setResult(newNotifications);
        }

        return deferredResultWrapper.getResult();
    }

    /**
     * 统一格式化
     *
     * @param appId
     * @param notifications
     * @return
     */
    private Map<String, ApolloConfigNotification> filterNotifications(String appId,
                                                                      List<ApolloConfigNotification> notifications) {
        //其中，KEY 为 Namespace 的名字
        Map<String, ApolloConfigNotification> filteredNotifications = Maps.newHashMap();

        for (ApolloConfigNotification notification : notifications) {

            if (Strings.isNullOrEmpty(notification.getNamespaceName())) {
                continue;
            }

            //strip out .properties suffix
            String originalNamespace = namespaceUtil.filterNamespaceName(notification.getNamespaceName());
            notification.setNamespaceName(originalNamespace);

            //fix the character case issue, such as FX.apollo <-> fx.apollo
            String normalizedNamespace = namespaceUtil.normalizeNamespace(appId, originalNamespace);

            // in case client side namespace name has character case issue and has difference notification ids
            // such as FX.apollo = 1 but fx.apollo = 2, we should let FX.apollo have the chance to update its notification id
            // which means we should record FX.apollo = 1 here and ignore fx.apollo = 2
            if (filteredNotifications.containsKey(normalizedNamespace) &&
                    filteredNotifications.get(normalizedNamespace).getNotificationId() < notification.getNotificationId()) {
                continue;
            }

            filteredNotifications.put(normalizedNamespace, notification);
        }
        return filteredNotifications;
    }

    private List<ApolloConfigNotification> getApolloConfigNotifications(Set<String> namespaces,
                                                                        Map<String, Long> clientSideNotifications,
                                                                        Multimap<String, String> watchedKeysMap,
                                                                        List<ReleaseMessage> latestReleaseMessages) {
        List<ApolloConfigNotification> newNotifications = Lists.newArrayList();

        if (!CollectionUtils.isEmpty(latestReleaseMessages)) {

            Map<String, Long> latestNotifications = Maps.newHashMap();

            for (ReleaseMessage releaseMessage : latestReleaseMessages) {
                latestNotifications.put(releaseMessage.getMessage(), releaseMessage.getId());
            }

            for (String namespace : namespaces) {

                long clientSideId = clientSideNotifications.get(namespace);
                long latestId = ConfigConsts.NOTIFICATION_ID_PLACEHOLDER;

                Collection<String> namespaceWatchedKeys = watchedKeysMap.get(namespace);

                for (String namespaceWatchedKey : namespaceWatchedKeys) {

                    long namespaceNotificationId =
                            latestNotifications.getOrDefault(namespaceWatchedKey, ConfigConsts.NOTIFICATION_ID_PLACEHOLDER);
                    if (namespaceNotificationId > latestId) {
                        latestId = namespaceNotificationId;
                    }

                }
                if (latestId > clientSideId) {

                    ApolloConfigNotification notification = new ApolloConfigNotification(namespace, latestId);
                    namespaceWatchedKeys.stream().filter(latestNotifications::containsKey).forEach(namespaceWatchedKey ->
                            notification.addMessage(namespaceWatchedKey, latestNotifications.get(namespaceWatchedKey)));
                    newNotifications.add(notification);

                }
            }
        }
        return newNotifications;
    }

    /**
     * 监听发布事件，通知客户端，获取最新配置
     *
     * @param message
     * @param channel
     */
    @Override
    public void handleMessage(ReleaseMessage message, String channel) {
        logger.info("message received - channel: {}, message: {}", channel, message);

        String content = message.getMessage();
        Tracer.logEvent("Apollo.LongPoll.Messages", content);
        if (!Topics.APOLLO_RELEASE_TOPIC.equals(channel) || Strings.isNullOrEmpty(content)) {
            return;
        }

        String changedNamespace = retrieveNamespaceFromReleaseMessage.apply(content);

        if (Strings.isNullOrEmpty(changedNamespace)) {
            logger.error("message format invalid - {}", content);
            return;
        }

        if (!deferredResults.containsKey(content)) {
            return;
        }

        //create a new list to avoid ConcurrentModificationException
        List<DeferredResultWrapper> results = Lists.newArrayList(deferredResults.get(content));

        ApolloConfigNotification configNotification = new ApolloConfigNotification(changedNamespace, message.getId());
        configNotification.addMessage(content, message.getId());

        //若需要通知的客户端过多，使用 ExecutorService 异步通知，避免“惊群效应”
        //do async notification if too many clients
        if (results.size() > bizConfig.releaseMessageNotificationBatch()) {
            largeNotificationBatchExecutorService.submit(() -> {
                logger.debug("Async notify {} clients for key {} with batch {}", results.size(), content,
                        bizConfig.releaseMessageNotificationBatch());
                for (int i = 0; i < results.size(); i++) {
                    if (i > 0 && i % bizConfig.releaseMessageNotificationBatch() == 0) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(bizConfig.releaseMessageNotificationBatchIntervalInMilli());
                        } catch (InterruptedException e) {
                            //ignore
                        }
                    }
                    logger.debug("Async notify {}", results.get(i));
                    results.get(i).setResult(configNotification);
                }
            });
            return;
        }

        logger.debug("Notify {} clients for key {}", results.size(), content);

        for (DeferredResultWrapper result : results) {
            result.setResult(configNotification);
        }
        logger.debug("Notification completed");
    }

    /**
     * 通过 ReleaseMessage 的消息内容，获得对应 Namespace 的名字
     */
    private static final Function<String, String> retrieveNamespaceFromReleaseMessage =
            releaseMessage -> {
                if (Strings.isNullOrEmpty(releaseMessage)) {
                    return null;
                }
                List<String> keys = STRING_SPLITTER.splitToList(releaseMessage);
                //message should be appId+cluster+namespace
                if (keys.size() != 3) {
                    logger.error("message format invalid - {}", releaseMessage);
                    return null;
                }
                return keys.get(2);
            };

    private void logWatchedKeys(Set<String> watchedKeys, String eventName) {
        for (String watchedKey : watchedKeys) {
            Tracer.logEvent(eventName, watchedKey);
        }
    }
}
