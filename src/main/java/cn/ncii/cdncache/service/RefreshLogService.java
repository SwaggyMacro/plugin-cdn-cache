package cn.ncii.cdncache.service;

import cn.ncii.cdncache.entity.RefreshLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static run.halo.app.extension.index.query.QueryFactory.all;

/**
 * 刷新日志服务
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshLogService {

    private final ReactiveExtensionClient client;

    /**
     * 记录刷新日志
     */
    public Mono<RefreshLog> saveLog(String provider, String triggerType, String postName,
                                     String postTitle, List<String> urls, boolean success,
                                     String taskId, String message, Instant requestTime) {
        RefreshLog refreshLog = new RefreshLog();
        
        // 初始化 metadata
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setName("refresh-log-" + UUID.randomUUID().toString().substring(0, 8));
        refreshLog.setMetadata(metadata);

        RefreshLog.RefreshLogSpec spec = new RefreshLog.RefreshLogSpec();
        spec.setProvider(provider);
        spec.setTriggerType(triggerType);
        spec.setPostName(postName);
        spec.setPostTitle(postTitle);
        spec.setUrls(urls);
        spec.setSuccess(success);
        spec.setTaskId(taskId);
        spec.setMessage(message);
        spec.setRequestTime(requestTime);
        spec.setResponseTime(Instant.now());
        spec.setDuration(Instant.now().toEpochMilli() - requestTime.toEpochMilli());

        refreshLog.setSpec(spec);

        log.info("准备保存刷新日志: provider={}, triggerType={}, success={}", provider, triggerType, success);
        
        return client.create(refreshLog)
                .doOnSuccess(saved -> log.info("刷新日志已保存: {}", saved.getMetadata().getName()))
                .doOnError(e -> log.error("保存刷新日志失败", e));
    }

    /**
     * 获取日志列表
     */
    public Flux<RefreshLog> listLogs(int limit) {
        return client.listAll(RefreshLog.class, new ListOptions(), null)
                .sort(Comparator.comparing(
                        (RefreshLog l) -> l.getMetadata().getCreationTimestamp(),
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .take(limit);
    }

    /**
     * 删除指定日志
     */
    public Mono<Void> deleteLog(String name) {
        return client.get(RefreshLog.class, name)
                .flatMap(client::delete)
                .then();
    }

    /**
     * 清空所有日志
     */
    public Mono<Void> clearAllLogs() {
        return client.listAll(RefreshLog.class, new ListOptions(), null)
                .flatMap(client::delete)
                .then();
    }
}
