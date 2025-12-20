package cn.ncii.cdncache.service;

import cn.ncii.cdncache.CdnSetting;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.core.extension.Plugin;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDN 刷新管理器
 * 统一处理刷新逻辑，包括插件状态检查和防抖
 */
@Slf4j
@Component
public class CdnRefreshManager {

    private static final String PLUGIN_NAME = "cdn-cache";
    private static final long DEBOUNCE_SECONDS = 5;

    private final ReactiveSettingFetcher settingFetcher;
    private final CdnRefreshServiceFactory serviceFactory;
    private final RefreshLogService logService;
    private final ReactiveExtensionClient client;

    // URL 防抖记录
    private final ConcurrentHashMap<String, Instant> urlDebounceMap = new ConcurrentHashMap<>();

    public CdnRefreshManager(ReactiveSettingFetcher settingFetcher,
                             CdnRefreshServiceFactory serviceFactory,
                             RefreshLogService logService,
                             ReactiveExtensionClient client) {
        this.settingFetcher = settingFetcher;
        this.serviceFactory = serviceFactory;
        this.logService = logService;
        this.client = client;
    }

    /**
     * 执行 CDN 刷新
     * @param urls 要刷新的 URL 列表
     * @param triggerType 触发类型
     * @param postName 文章名称（可选）
     * @param postTitle 文章标题（可选）
     */
    public Mono<Void> refresh(List<String> urls, String triggerType, String postName, String postTitle) {
        if (urls == null || urls.isEmpty()) {
            return Mono.empty();
        }

        // 先检查插件是否启用
        return isPluginEnabled()
                .flatMap(pluginEnabled -> {
                    if (!pluginEnabled) {
                        log.debug("CDN-CACHE: 插件未启用，跳过刷新");
                        return Mono.empty();
                    }
                    return getSettings();
                })
                .flatMap(setting -> {
                    if (!Boolean.TRUE.equals(setting.getEnabled())) {
                        log.debug("CDN-CACHE: 功能未启用，跳过刷新");
                        return Mono.empty();
                    }

                    // Cloudflare 使用单独的 token，其他提供商使用 accessKey
                    if (setting.getProvider() == CdnSetting.CdnProvider.CLOUDFLARE) {
                        if (StringUtils.isBlank(setting.getCloudflareToken())) {
                            log.warn("CDN-CACHE: 缺少 Cloudflare Token，跳过刷新");
                            return Mono.empty();
                        }
                    } else {
                        if (StringUtils.isBlank(setting.getAccessKeyId()) ||
                                StringUtils.isBlank(setting.getAccessKeySecret())) {
                            log.warn("CDN-CACHE: 缺少 AccessKey，跳过刷新");
                            return Mono.empty();
                        }
                    }

                    // 过滤掉防抖期内的 URL
                    List<String> filteredUrls = filterDebouncedUrls(urls);
                    if (filteredUrls.isEmpty()) {
                        log.info("CDN-CACHE: 所有 URL 在防抖期内，跳过刷新");
                        return Mono.empty();
                    }

                    log.info("CDN-CACHE: 刷新 {} 个 URL: {}", filteredUrls.size(), filteredUrls);

                    Instant requestTime = Instant.now();
                    String provider = setting.getProvider().name();

                    CdnRefreshService refreshService = serviceFactory.createService(setting);
                    return refreshService.refreshUrls(filteredUrls)
                            .doOnNext(result -> log.info("CDN-CACHE: 刷新结果 success={}", result.success()))
                            .flatMap(result -> logService.saveLog(
                                    provider, triggerType, postName, postTitle,
                                    filteredUrls, result.success(), result.taskId(),
                                    result.message(), requestTime
                            ).then());
                })
                .onErrorResume(e -> {
                    log.error("CDN-CACHE: 刷新错误", e);
                    return Mono.empty();
                });
    }

    /**
     * 检查插件是否启用
     */
    private Mono<Boolean> isPluginEnabled() {
        return client.get(Plugin.class, PLUGIN_NAME)
                .map(plugin -> {
                    if (plugin.getSpec() == null) {
                        return false;
                    }
                    return Boolean.TRUE.equals(plugin.getSpec().getEnabled());
                })
                .defaultIfEmpty(false)
                .onErrorReturn(false);
    }

    /**
     * 过滤掉防抖期内的 URL
     */
    private List<String> filterDebouncedUrls(List<String> urls) {
        Instant now = Instant.now();
        
        // 清理过期的防抖记录
        urlDebounceMap.entrySet().removeIf(entry -> 
                now.getEpochSecond() - entry.getValue().getEpochSecond() > DEBOUNCE_SECONDS * 2);

        return urls.stream()
                .filter(url -> {
                    Instant lastRefresh = urlDebounceMap.get(url);
                    if (lastRefresh != null && 
                            now.getEpochSecond() - lastRefresh.getEpochSecond() < DEBOUNCE_SECONDS) {
                        log.debug("CDN-CACHE: URL {} 在防抖期内，跳过", url);
                        return false;
                    }
                    urlDebounceMap.put(url, now);
                    return true;
                })
                .toList();
    }

    /**
     * 获取设置
     */
    public Mono<CdnSetting> getSettings() {
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
                            if (refresh.has("refreshOnComment")) setting.setRefreshOnComment(refresh.get("refreshOnComment").asBoolean(true));
                            if (refresh.has("customPaths")) setting.setCustomPaths(refresh.get("customPaths").asText());
                        })
                        .then(Mono.just(setting))
                        .switchIfEmpty(Mono.just(setting))
        );
    }
}
