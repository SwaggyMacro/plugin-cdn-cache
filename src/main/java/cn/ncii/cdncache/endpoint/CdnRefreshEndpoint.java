package cn.ncii.cdncache.endpoint;

import cn.ncii.cdncache.CdnSetting;
import cn.ncii.cdncache.entity.CdnProviderConfig;
import cn.ncii.cdncache.entity.RefreshLog;
import cn.ncii.cdncache.service.CdnRefreshService;
import cn.ncii.cdncache.service.CdnRefreshServiceFactory;
import cn.ncii.cdncache.service.RefreshLogService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

/**
 * CDN 缓存刷新 API 端点
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Slf4j
@Component
public class CdnRefreshEndpoint implements CustomEndpoint {

    private final ReactiveSettingFetcher settingFetcher;
    private final CdnRefreshServiceFactory serviceFactory;
    private final RefreshLogService logService;

    public CdnRefreshEndpoint(ReactiveSettingFetcher settingFetcher,
                              CdnRefreshServiceFactory serviceFactory,
                              RefreshLogService logService) {
        this.settingFetcher = settingFetcher;
        this.serviceFactory = serviceFactory;
        this.logService = logService;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("cdn-cache.halo.run/v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "CdnRefreshV1alpha1";
        return SpringdocRouteBuilder.route()
                .POST("/refresh", this::refresh,
                        builder -> builder.operationId("RefreshCdnCache")
                                .tag(tag)
                                .description("手动刷新 CDN 缓存")
                                .requestBody(requestBodyBuilder()
                                        .description("刷新请求")
                                        .implementation(RefreshRequest.class))
                                .response(responseBuilder()
                                        .implementation(RefreshResponse.class)))
                .GET("/logs", this::listLogs,
                        builder -> builder.operationId("ListRefreshLogs")
                                .tag(tag)
                                .description("获取刷新日志列表")
                                .parameter(parameterBuilder().name("limit").description("返回数量限制").implementation(Integer.class))
                                .response(responseBuilder()
                                        .implementationArray(RefreshLog.class)))
                .DELETE("/logs/{name}", this::deleteLog,
                        builder -> builder.operationId("DeleteRefreshLog")
                                .tag(tag)
                                .description("删除指定日志")
                                .parameter(parameterBuilder().name("name").description("日志名称").implementation(String.class))
                                .response(responseBuilder()
                                        .implementation(RefreshResponse.class)))
                .DELETE("/logs", this::clearLogs,
                        builder -> builder.operationId("ClearRefreshLogs")
                                .tag(tag)
                                .description("清空所有日志")
                                .response(responseBuilder()
                                        .implementation(RefreshResponse.class)))
                .build();
    }

    private Mono<ServerResponse> refresh(ServerRequest request) {
        Instant requestTime = Instant.now();
        return request.bodyToMono(RefreshRequest.class)
                .flatMap(req -> getSettings()
                        .flatMap(setting -> {
                            if (!Boolean.TRUE.equals(setting.getEnabled())) {
                                return ServerResponse.badRequest()
                                        .bodyValue(new RefreshResponse(false, "CDN 缓存刷新功能未启用", null));
                            }

                            if (req.urls() == null || req.urls().isEmpty()) {
                                return ServerResponse.badRequest()
                                        .bodyValue(new RefreshResponse(false, "URL 列表不能为空", null));
                            }

                            List<CdnProviderConfig> enabledProviders = setting.getEnabledProviders();
                            if (enabledProviders.isEmpty()) {
                                return ServerResponse.badRequest()
                                        .bodyValue(new RefreshResponse(false, "没有配置有效的 CDN 提供商", null));
                            }

                            // 对所有启用的 CDN 提供商执行刷新
                            return Flux.fromIterable(enabledProviders)
                                    .flatMap(providerConfig -> {
                                        String providerName = providerConfig.getName() != null ?
                                                providerConfig.getName() : providerConfig.getProviderDisplayName();
                                        String provider = providerConfig.getProvider().name();
                                        
                                        CdnRefreshService refreshService = serviceFactory.createService(providerConfig);
                                        return refreshService.refreshUrls(req.urls())
                                                .flatMap(result -> logService.saveLog(
                                                        providerName + " (" + provider + ")", "MANUAL", null, null,
                                                        req.urls(), result.success(), result.taskId(),
                                                        result.message(), requestTime
                                                ).thenReturn(result));
                                    })
                                    .collectList()
                                    .flatMap(results -> {
                                        boolean allSuccess = results.stream().allMatch(CdnRefreshService.RefreshResult::success);
                                        long successCount = results.stream().filter(CdnRefreshService.RefreshResult::success).count();
                                        String message = String.format("刷新完成: %d/%d 个 CDN 提供商成功", 
                                                successCount, results.size());
                                        return ServerResponse.ok()
                                                .bodyValue(new RefreshResponse(allSuccess, message, null));
                                    });
                        }))
                .onErrorResume(e -> {
                    log.error("刷新 CDN 缓存失败", e);
                    return ServerResponse.badRequest()
                            .bodyValue(new RefreshResponse(false, "刷新失败: " + e.getMessage(), null));
                });
    }

    private Mono<ServerResponse> listLogs(ServerRequest request) {
        int limit = request.queryParam("limit")
                .map(Integer::parseInt)
                .orElse(50);
        return logService.listLogs(limit)
                .collectList()
                .flatMap(logs -> ServerResponse.ok().bodyValue(logs));
    }

    private Mono<ServerResponse> deleteLog(ServerRequest request) {
        String name = request.pathVariable("name");
        return logService.deleteLog(name)
                .then(ServerResponse.ok().bodyValue(new RefreshResponse(true, "删除成功", null)))
                .onErrorResume(e -> ServerResponse.badRequest()
                        .bodyValue(new RefreshResponse(false, "删除失败: " + e.getMessage(), null)));
    }

    private Mono<ServerResponse> clearLogs(ServerRequest request) {
        return logService.clearAllLogs()
                .then(ServerResponse.ok().bodyValue(new RefreshResponse(true, "清空成功", null)))
                .onErrorResume(e -> ServerResponse.badRequest()
                        .bodyValue(new RefreshResponse(false, "清空失败: " + e.getMessage(), null)));
    }

    private Mono<CdnSetting> getSettings() {
        Mono<CdnSetting> basicMono = settingFetcher.get("basic")
                .map(basic -> {
                    CdnSetting s = new CdnSetting();
                    if (basic.has("enabled")) s.setEnabled(basic.get("enabled").asBoolean(false));
                    if (basic.has("siteDomain")) s.setSiteDomain(basic.get("siteDomain").asText());
                    return s;
                })
                .switchIfEmpty(Mono.just(new CdnSetting()));

        return basicMono.flatMap(setting ->
                settingFetcher.get("providers")
                        .doOnNext(providersNode -> {
                            if (providersNode.has("cdnProviders") && providersNode.get("cdnProviders").isArray()) {
                                List<CdnProviderConfig> providers = new ArrayList<>();
                                providersNode.get("cdnProviders").forEach(node -> {
                                    CdnProviderConfig config = new CdnProviderConfig();
                                    if (node.has("name")) config.setName(node.get("name").asText());
                                    if (node.has("enabled")) config.setEnabled(node.get("enabled").asBoolean(true));
                                    if (node.has("provider")) {
                                        try {
                                            config.setProvider(CdnSetting.CdnProvider.valueOf(node.get("provider").asText()));
                                        } catch (Exception e) {
                                            log.warn("无效的 CDN 提供商类型: {}", node.get("provider").asText());
                                        }
                                    }
                                    if (node.has("accessKeyId")) config.setAccessKeyId(node.get("accessKeyId").asText());
                                    if (node.has("accessKeySecret")) config.setAccessKeySecret(node.get("accessKeySecret").asText());
                                    if (node.has("cloudflareToken")) config.setCloudflareToken(node.get("cloudflareToken").asText());
                                    if (node.has("zoneId")) config.setZoneId(node.get("zoneId").asText());
                                    // 自定义 PURGE 配置
                                    if (node.has("successKeyword")) config.setSuccessKeyword(node.get("successKeyword").asText());
                                    if (node.has("customHeadersText")) config.setCustomHeadersText(node.get("customHeadersText").asText());
                                    providers.add(config);
                                });
                                setting.setProviders(providers);
                            }
                        })
                        .then(Mono.just(setting))
                        .switchIfEmpty(Mono.just(setting))
        ).flatMap(setting -> 
            settingFetcher.get("routes")
                .doOnNext(routes -> {
                    if (routes.has("archiveRoute")) setting.setArchiveRoute(routes.get("archiveRoute").asText("archives"));
                    if (routes.has("categoryRoute")) setting.setCategoryRoute(routes.get("categoryRoute").asText("categories"));
                    if (routes.has("tagRoute")) setting.setTagRoute(routes.get("tagRoute").asText("tags"));
                })
                .then(Mono.just(setting))
                .switchIfEmpty(Mono.just(setting))
        ).flatMap(setting ->
            settingFetcher.get("refresh")
                .doOnNext(refresh -> {
                    if (refresh.has("refreshHomePage")) setting.setRefreshHomePage(refresh.get("refreshHomePage").asBoolean(true));
                    if (refresh.has("refreshArchivePage")) setting.setRefreshArchivePage(refresh.get("refreshArchivePage").asBoolean(true));
                    if (refresh.has("refreshCategoryPage")) setting.setRefreshCategoryPage(refresh.get("refreshCategoryPage").asBoolean(true));
                    if (refresh.has("refreshTagPage")) setting.setRefreshTagPage(refresh.get("refreshTagPage").asBoolean(true));
                    if (refresh.has("customPaths")) setting.setCustomPaths(refresh.get("customPaths").asText());
                })
                .then(Mono.just(setting))
                .switchIfEmpty(Mono.just(setting))
        ).onErrorResume(e -> {
            log.warn("获取设置失败，使用默认配置", e);
            return Mono.just(new CdnSetting());
        });
    }

    public record RefreshRequest(List<String> urls) {}
    public record RefreshResponse(boolean success, String message, String taskId) {}
}
