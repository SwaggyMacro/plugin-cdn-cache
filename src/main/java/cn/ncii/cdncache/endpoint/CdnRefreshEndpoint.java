package cn.ncii.cdncache.endpoint;

import cn.ncii.cdncache.CdnSetting;
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
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
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

                            String provider = setting.getProvider().name();
                            CdnRefreshService refreshService = serviceFactory.createService(setting);
                            return refreshService.refreshUrls(req.urls())
                                    .flatMap(result -> logService.saveLog(
                                            provider, "MANUAL", null, null,
                                            req.urls(), result.success(), result.taskId(),
                                            result.message(), requestTime
                                    ).then(ServerResponse.ok()
                                            .bodyValue(new RefreshResponse(result.success(), result.message(), result.taskId()))));
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
                    if (basic.has("provider")) {
                        try { s.setProvider(CdnSetting.CdnProvider.valueOf(basic.get("provider").asText())); }
                        catch (Exception e) { s.setProvider(CdnSetting.CdnProvider.ALIYUN); }
                    }
                    if (basic.has("accessKeyId")) s.setAccessKeyId(basic.get("accessKeyId").asText());
                    if (basic.has("accessKeySecret")) s.setAccessKeySecret(basic.get("accessKeySecret").asText());
                    if (basic.has("cloudflareToken")) s.setCloudflareToken(basic.get("cloudflareToken").asText());
                    if (basic.has("zoneId")) s.setZoneId(basic.get("zoneId").asText());
                    if (basic.has("siteDomain")) s.setSiteDomain(basic.get("siteDomain").asText());
                    return s;
                })
                .switchIfEmpty(Mono.just(new CdnSetting()));

        return basicMono.flatMap(setting -> 
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
