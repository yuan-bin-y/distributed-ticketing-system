package com.byy.ticket.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 下单与恢复参数。租约用于任务领取；重试参数用于持久化退避，服务重启后仍有效。
 * @param devIdentityEnabled 是否显式接受开发身份头，默认关闭。
 * @param paymentWindow 订单支付窗口。
 * @param recoveryEnabled 是否运行后台核对。
 * @param batchSize 每轮扫描条数。
 * @param leaseDuration 单次任务领取租约。
 * @param retryBase 首次重试间隔。
 * @param retryMax 重试间隔上限。
 */
@ConfigurationProperties("ticket.order")
public record OrderWorkflowProperties(boolean devIdentityEnabled, Duration paymentWindow,
                                      Boolean recoveryEnabled, Integer batchSize, Duration leaseDuration,
                                      Duration retryBase, Duration retryMax) {
    /** 设置默认值并检查边界，避免无效租约或无限制扫描。 */
    public OrderWorkflowProperties {
        paymentWindow = paymentWindow == null ? Duration.ofMinutes(15) : paymentWindow;
        recoveryEnabled = recoveryEnabled == null ? true : recoveryEnabled;
        batchSize = batchSize == null ? 20 : batchSize;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration;
        retryBase = retryBase == null ? Duration.ofSeconds(5) : retryBase;
        retryMax = retryMax == null ? Duration.ofMinutes(5) : retryMax;
        if (paymentWindow.compareTo(Duration.ofSeconds(1)) < 0 || paymentWindow.compareTo(Duration.ofDays(1)) > 0
                || batchSize < 1 || batchSize > 100 || leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                || retryBase.compareTo(Duration.ofMillis(1)) < 0 || retryMax.compareTo(retryBase) < 0
                || retryMax.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("订单窗口、批量大小或恢复时间配置不正确");
        }
    }
}
