package cn.ncii.cdncache.service.impl;

import cn.ncii.cdncache.entity.CdnProviderConfig;
import cn.ncii.cdncache.service.CdnRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Cloudflare CDN 缓存刷新服务实现
 * 配置说明：
 * - Zone ID: 填写在 "Zone ID / Site ID" 字段
 * - Cloudflare API Token: 填写在 "Cloudflare API Token" 字段（需要有 Zone.Cache Purge 权限）
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Slf4j
public class CloudflareCdnRefreshService implements CdnRefreshService {

    private static final String CLOUDFLARE_API_ENDPOINT = "https://api.cloudflare.com/client/v4";

    private final WebClient webClient;
    private final CdnProviderConfig config;

    public CloudflareCdnRefreshService(CdnProviderConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                .baseUrl(CLOUDFLARE_API_ENDPOINT)
                .defaultHeader("Authorization", "Bearer " + config.getCloudflareToken())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public Mono<RefreshResult> refreshUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.just(RefreshResult.failure("URL 列表为空"));
        }

        String token = config.getCloudflareToken();
        String zoneId = config.getZoneId();
        
        log.info("Cloudflare CDN 刷新请求: zoneId={}, token长度={}, urls={}", 
                zoneId, token != null ? token.length() : 0, urls);
        
        if (token == null || token.isBlank()) {
            return Mono.just(RefreshResult.failure("Cloudflare API Token 未配置"));
        }
        
        if (zoneId == null || zoneId.isBlank()) {
            return Mono.just(RefreshResult.failure("Cloudflare Zone ID 未配置"));
        }

        // 构建请求体
        StringBuilder filesJson = new StringBuilder("{\"files\":[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) filesJson.append(",");
            filesJson.append("\"").append(urls.get(i)).append("\"");
        }
        filesJson.append("]}");

        // 每次请求时动态设置 Authorization header，避免构造函数时 token 为空
        return WebClient.create()
                .post()
                .uri(CLOUDFLARE_API_ENDPOINT + "/zones/" + zoneId + "/purge_cache")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(filesJson.toString())
                .exchangeToMono(response -> {
                    return response.bodyToMono(String.class)
                            .map(body -> {
                                log.info("Cloudflare CDN 响应状态: {}, 响应体: {}", response.statusCode(), body);
                                if (response.statusCode().is2xxSuccessful() && body.contains("\"success\":true")) {
                                    return RefreshResult.success(extractId(body));
                                } else {
                                    return RefreshResult.failure("刷新失败: " + body);
                                }
                            });
                })
                .onErrorResume(e -> {
                    log.error("Cloudflare CDN 刷新异常", e);
                    return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                });
    }

    @Override
    public Mono<RefreshResult> refreshDirectories(List<String> directories) {
        // Cloudflare 不直接支持目录刷新，需要使用 purge_everything 或者转换为具体 URL
        // 这里使用 purge_everything 作为目录刷新的替代方案
        log.warn("Cloudflare 不支持目录刷新，将执行全站缓存清除");

        String token = config.getCloudflareToken();
        String zoneId = config.getZoneId();
        
        if (token == null || token.isBlank()) {
            return Mono.just(RefreshResult.failure("Cloudflare API Token 未配置"));
        }

        return WebClient.create()
                .post()
                .uri(CLOUDFLARE_API_ENDPOINT + "/zones/" + zoneId + "/purge_cache")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"purge_everything\":true}")
                .exchangeToMono(response -> {
                    return response.bodyToMono(String.class)
                            .map(body -> {
                                log.info("Cloudflare CDN 全站刷新响应状态: {}, 响应体: {}", response.statusCode(), body);
                                if (response.statusCode().is2xxSuccessful() && body.contains("\"success\":true")) {
                                    return RefreshResult.success("purge_everything");
                                } else {
                                    return RefreshResult.failure("刷新失败: " + body);
                                }
                            });
                })
                .onErrorResume(e -> {
                    log.error("Cloudflare CDN 刷新异常", e);
                    return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                });
    }

    private String extractId(String response) {
        int start = response.indexOf("\"id\":\"");
        if (start > 0) {
            start += 6;
            int end = response.indexOf("\"", start);
            if (end > start) {
                return response.substring(start, end);
            }
        }
        return "unknown";
    }
}
