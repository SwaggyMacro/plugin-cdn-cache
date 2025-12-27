package cn.ncii.cdncache.service.impl;

import cn.ncii.cdncache.entity.CdnProviderConfig;
import cn.ncii.cdncache.service.CdnRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 自定义 PURGE 方法 CDN 缓存刷新服务实现
 * 使用 HTTP PURGE 方法直接请求需要清除缓存的 URL
 *
 * @author SwaggyMacro
 * @since 1.1.0
 */
@Slf4j
public class CustomPurgeCdnRefreshService implements CdnRefreshService {

    private final CdnProviderConfig config;
    private final WebClient webClient;

    public CustomPurgeCdnRefreshService(CdnProviderConfig config) {
        this.config = config;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public Mono<RefreshResult> refreshUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.just(RefreshResult.failure("URL 列表为空"));
        }

        String successKeyword = config.getSuccessKeyword();
        if (successKeyword == null || successKeyword.isBlank()) {
            return Mono.just(RefreshResult.failure("未配置成功判断关键字"));
        }

        log.info("自定义 PURGE 刷新请求: urls={}, successKeyword={}", urls, successKeyword);

        // 对每个 URL 发送 PURGE 请求
        return Flux.fromIterable(urls)
                .flatMap(url -> purgeUrl(url, successKeyword))
                .collectList()
                .map(results -> {
                    long successCount = results.stream().filter(r -> r).count();
                    long totalCount = results.size();
                    
                    if (successCount == totalCount) {
                        return RefreshResult.success("purge-" + System.currentTimeMillis());
                    } else if (successCount > 0) {
                        return new RefreshResult(true, 
                                String.format("部分成功: %d/%d 个 URL 清除成功", successCount, totalCount),
                                "purge-partial-" + System.currentTimeMillis());
                    } else {
                        return RefreshResult.failure("所有 URL 清除失败");
                    }
                })
                .onErrorResume(e -> {
                    log.error("自定义 PURGE 刷新异常", e);
                    return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                });
    }

    @Override
    public Mono<RefreshResult> refreshDirectories(List<String> directories) {
        // PURGE 方法通常不支持目录刷新，转换为 URL 刷新
        log.warn("自定义 PURGE 不支持目录刷新，将尝试作为 URL 刷新");
        return refreshUrls(directories);
    }

    /**
     * 对单个 URL 发送 PURGE 请求
     */
    private Mono<Boolean> purgeUrl(String url, String successKeyword) {
        try {
            WebClient.RequestBodySpec requestSpec = webClient
                    .method(HttpMethod.valueOf("PURGE"))
                    .uri(url);

            // 添加自定义 Headers
            if (config.getCustomHeaders() != null && !config.getCustomHeaders().isEmpty()) {
                for (CdnProviderConfig.CustomHeader header : config.getCustomHeaders()) {
                    if (header.getHeaderName() != null && !header.getHeaderName().isBlank()
                            && header.getHeaderValue() != null && !header.getHeaderValue().isBlank()) {
                        requestSpec = (WebClient.RequestBodySpec) requestSpec.header(header.getHeaderName(), header.getHeaderValue());
                    }
                }
            }

            return requestSpec
                    .exchangeToMono(response -> {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    boolean success = body.contains(successKeyword);
                                    log.info("PURGE {} - 状态: {}, 成功: {}, 响应: {}", 
                                            url, response.statusCode(), success, 
                                            body.length() > 200 ? body.substring(0, 200) + "..." : body);
                                    return success;
                                });
                    })
                    .onErrorResume(e -> {
                        log.error("PURGE {} 失败: {}", url, e.getMessage());
                        return Mono.just(false);
                    });
        } catch (Exception e) {
            log.error("构建 PURGE 请求失败: {}", url, e);
            return Mono.just(false);
        }
    }
}
