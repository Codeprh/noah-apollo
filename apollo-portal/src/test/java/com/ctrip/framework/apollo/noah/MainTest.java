package com.ctrip.framework.apollo.noah;

import com.ctrip.framework.apollo.core.utils.ApolloThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 描述:
 * 测试主类
 *
 * @author Noah
 * @create 2020-10-07 8:04 上午
 */
public class MainTest {
    public static void main(String[] args) {

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1, ApolloThreadFactory
                .create("ReleaseMessageScanner", true));
        long databaseScanInterval = 1000l;

        executorService.scheduleWithFixedDelay((Runnable) () -> {
            System.out.println("hello");
        }, databaseScanInterval, databaseScanInterval, TimeUnit.MILLISECONDS);
        
        try {
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    public static void main1(String[] args) {

        ScheduledExecutorService refreshServiceAddressService =
                Executors.newScheduledThreadPool(1, ApolloThreadFactory.create("noahTest", true));

        refreshServiceAddressService.schedule(() -> {
            System.out.println("hello noah");
        }, 1, TimeUnit.MILLISECONDS);

        try {
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
