package cn.ncii.cdncache.service;

import cn.ncii.cdncache.CdnSetting;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 定时刷新/预热 URI 任务
 */
@Slf4j
@Component
public class ScheduledUriRefreshTask {

    private static final String DEFAULT_CRON = "0 0 3 * * *";
    private static final List<String> DEFAULT_EXCLUDED_PREFIXES = List.of("/console", "/api");

    private final CdnRefreshManager refreshManager;
    private final WebClient webClient;

    private ZonedDateTime lastTriggeredAt;

    public ScheduledUriRefreshTask(CdnRefreshManager refreshManager) {
        this.refreshManager = refreshManager;
        this.webClient = WebClient.builder().build();
    }

    @Scheduled(cron = "0 * * * * *")
    public void run() {
        refreshManager.getSettings()
                .flatMap(setting -> {
                    if (!Boolean.TRUE.equals(setting.getScheduledRefreshEnabled())) {
                        return Mono.empty();
                    }
                    if (!shouldTrigger(setting.getScheduledRefreshCron())) {
                        return Mono.empty();
                    }
                    return resolveTargetUrls(setting)
                            .flatMap(urls -> {
                                if (urls.isEmpty()) {
                                    log.info("CDN-CACHE: 定时刷新未找到可用 URI，跳过");
                                    return Mono.empty();
                                }
                                log.info("CDN-CACHE: 定时刷新/预热 URI 数量: {}", urls.size());
                                return refreshManager.refresh(urls, "SCHEDULED", null, null);
                            });
                })
                .subscribe(
                        v -> {
                        },
                        e -> log.error("CDN-CACHE: 定时刷新任务执行失败", e)
                );
    }

    synchronized boolean shouldTrigger(String cron) {
        String cronExpr = StringUtils.defaultIfBlank(cron, DEFAULT_CRON);
        CronExpression expression;
        try {
            expression = CronExpression.parse(cronExpr);
        } catch (IllegalArgumentException e) {
            expression = CronExpression.parse(DEFAULT_CRON);
        }

        ZonedDateTime now = ZonedDateTime.now().withSecond(0).withNano(0);
        ZonedDateTime previousMinute = now.minusMinutes(1);
        ZonedDateTime next = expression.next(previousMinute);
        boolean shouldRun = next != null && !next.isAfter(now);
        if (!shouldRun) {
            return false;
        }

        if (lastTriggeredAt != null && lastTriggeredAt.equals(now)) {
            return false;
        }
        lastTriggeredAt = now;
        return true;
    }

    Mono<List<String>> resolveTargetUrls(CdnSetting setting) {
        String siteDomain = StringUtils.trimToEmpty(setting.getSiteDomain());
        if (StringUtils.isBlank(siteDomain)) {
            return Mono.just(List.of());
        }
        String normalizedSiteDomain = siteDomain.endsWith("/") ? siteDomain.substring(0, siteDomain.length() - 1) : siteDomain;
        URI siteUri = URI.create(normalizedSiteDomain);
        String sitemapUrl = normalizedSiteDomain + "/sitemap.xml";

        return fetchSitemapUrls(sitemapUrl, siteUri)
                .map(urls -> filterByDirectories(urls, setting.getScheduledRefreshDirectories()))
                .onErrorResume(e -> {
                    log.warn("CDN-CACHE: 获取 sitemap 失败，退化为刷新首页: {}", e.getMessage());
                    return Mono.just(List.of(normalizedSiteDomain + "/"));
                });
    }

    private Mono<List<String>> fetchSitemapUrls(String sitemapUrl, URI siteUri) {
        return fetchLocUrls(sitemapUrl)
                .flatMap(locUrls -> {
                    List<String> pageUrls = new ArrayList<>();
                    List<String> nestedSitemaps = new ArrayList<>();
                    for (String url : locUrls) {
                        if (isNestedSitemap(url)) {
                            nestedSitemaps.add(url);
                        } else {
                            pageUrls.add(url);
                        }
                    }

                    if (nestedSitemaps.isEmpty()) {
                        return Mono.just(filterBySiteAndExclusion(pageUrls, siteUri));
                    }

                    return Flux.fromIterable(nestedSitemaps)
                            .flatMap(this::fetchLocUrls)
                            .flatMapIterable(list -> list)
                            .collectList()
                            .map(nestedLocs -> {
                                pageUrls.addAll(nestedLocs);
                                return filterBySiteAndExclusion(pageUrls, siteUri);
                            });
                });
    }

    private Mono<List<String>> fetchLocUrls(String sitemapUrl) {
        return webClient.get()
                .uri(sitemapUrl)
                .retrieve()
                .bodyToMono(String.class)
                .map(xml -> extractLocUrlsFromSitemap(xml, sitemapUrl));
    }

    List<String> extractLocUrlsFromSitemap(String xml) {
        return extractLocUrlsFromSitemap(xml, "unknown");
    }

    private List<String> extractLocUrlsFromSitemap(String xml, String sitemapUrl) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList locNodes = doc.getElementsByTagName("loc");
            Set<String> urls = new LinkedHashSet<>();
            for (int i = 0; i < locNodes.getLength(); i++) {
                String url = StringUtils.trimToEmpty(locNodes.item(i).getTextContent());
                if (StringUtils.isNotBlank(url)) {
                    urls.add(url);
                }
            }
            return new ArrayList<>(urls);
        } catch (Exception e) {
            throw new IllegalStateException("解析 sitemap 失败: " + sitemapUrl, e);
        }
    }

    List<String> filterByDirectories(List<String> urls, String directoriesConfig) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<String> includeDirectories = parseDirectories(directoriesConfig);
        Set<String> filtered = new LinkedHashSet<>();
        for (String url : urls) {
            try {
                URI uri = URI.create(url);
                String path = normalizePath(uri.getPath());
                if (isExcludedPath(path)) {
                    continue;
                }

                if (includeDirectories.isEmpty()) {
                    filtered.add(url);
                    continue;
                }

                boolean match = includeDirectories.stream().anyMatch(dir -> directoryMatches(path, dir));
                if (match) {
                    filtered.add(url);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ArrayList<>(filtered);
    }

    private List<String> filterBySiteAndExclusion(List<String> urls, URI siteUri) {
        String expectedHost = siteUri.getHost();
        String expectedScheme = siteUri.getScheme();
        Set<String> filtered = new LinkedHashSet<>();
        for (String url : urls) {
            try {
                URI uri = URI.create(url);
                if (StringUtils.isBlank(uri.getHost())
                        || !StringUtils.equalsIgnoreCase(uri.getHost(), expectedHost)
                        || !StringUtils.equalsIgnoreCase(uri.getScheme(), expectedScheme)) {
                    continue;
                }
                if (isExcludedPath(normalizePath(uri.getPath()))) {
                    continue;
                }
                filtered.add(url);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ArrayList<>(filtered);
    }

    private List<String> parseDirectories(String directoriesConfig) {
        if (StringUtils.isBlank(directoriesConfig)) {
            return List.of();
        }
        Set<String> directories = new LinkedHashSet<>();
        for (String dir : directoriesConfig.split(",")) {
            String normalized = normalizePath(dir.trim());
            if (StringUtils.isNotBlank(normalized)) {
                directories.add(normalized);
            }
        }
        return new ArrayList<>(directories);
    }

    private boolean isNestedSitemap(String url) {
        try {
            URI uri = URI.create(url);
            String path = StringUtils.lowerCase(StringUtils.defaultString(uri.getPath()));
            return path.endsWith(".xml") && path.contains("sitemap");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isExcludedPath(String path) {
        return DEFAULT_EXCLUDED_PREFIXES.stream().anyMatch(prefix -> directoryMatches(path, prefix));
    }

    private boolean directoryMatches(String path, String directory) {
        if ("/".equals(directory)) {
            return true;
        }
        return path.equals(directory) || path.startsWith(directory + "/");
    }

    private String normalizePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
