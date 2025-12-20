package cn.ncii.cdncache.service;

import reactor.core.publisher.Mono;
import java.util.List;

/**
 * CDN 缓存刷新服务接口
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
public interface CdnRefreshService {

    /**
     * 刷新指定 URL 的缓存
     *
     * @param urls 需要刷新的 URL 列表
     * @return 刷新结果
     */
    Mono<RefreshResult> refreshUrls(List<String> urls);

    /**
     * 刷新指定目录的缓存
     *
     * @param directories 需要刷新的目录列表
     * @return 刷新结果
     */
    Mono<RefreshResult> refreshDirectories(List<String> directories);

    /**
     * 刷新结果
     */
    record RefreshResult(boolean success, String message, String taskId) {
        public static RefreshResult success(String taskId) {
            return new RefreshResult(true, "刷新任务提交成功", taskId);
        }

        public static RefreshResult failure(String message) {
            return new RefreshResult(false, message, null);
        }
    }
}
