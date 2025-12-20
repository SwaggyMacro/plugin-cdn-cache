package cn.ncii.cdncache;

import cn.ncii.cdncache.service.CdnRefreshManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立页面 Reconciler
 * 监听页面发布/更新事件，刷新 CDN 缓存
 */
@Slf4j
@Component
public class PagePublishListener implements Reconciler<Reconciler.Request> {

    private final CdnRefreshManager refreshManager;
    private final ExtensionClient client;

    public PagePublishListener(CdnRefreshManager refreshManager, ExtensionClient client) {
        this.refreshManager = refreshManager;
        this.client = client;
        log.info("CDN-CACHE: PagePublishListener 已初始化");
    }

    @Override
    public Result reconcile(Request request) {
        String pageName = request.name();

        client.fetch(SinglePage.class, pageName).ifPresent(page -> {
            if (!isPublished(page)) {
                return;
            }

            log.info("CDN-CACHE: 页面已发布: {}", page.getSpec().getTitle());

            refreshManager.getSettings()
                    .flatMap(setting -> {
                        List<String> urls = buildRefreshUrls(setting, page);
                        return refreshManager.refresh(urls, "PAGE_UPDATE",
                                page.getMetadata().getName(), page.getSpec().getTitle());
                    })
                    .subscribe(
                            v -> {},
                            e -> log.error("CDN-CACHE: 页面处理失败", e)
                    );
        });

        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new SinglePage())
                .build();
    }

    private boolean isPublished(SinglePage page) {
        SinglePage.SinglePageStatus status = page.getStatus();
        if (status == null) return false;
        // SinglePage 使用 "PUBLISHED" 字符串
        return "PUBLISHED".equals(status.getPhase());
    }

    private List<String> buildRefreshUrls(CdnSetting setting, SinglePage page) {
        List<String> urls = new ArrayList<>();
        String siteDomain = setting.getSiteDomain();
        if (StringUtils.isBlank(siteDomain)) return urls;
        if (!siteDomain.endsWith("/")) siteDomain = siteDomain + "/";

        SinglePage.SinglePageStatus status = page.getStatus();
        if (status != null && StringUtils.isNotBlank(status.getPermalink())) {
            String permalink = status.getPermalink();
            if (permalink.startsWith("http://") || permalink.startsWith("https://")) {
                urls.add(permalink);
            } else if (permalink.startsWith("/")) {
                urls.add(siteDomain.substring(0, siteDomain.length() - 1) + permalink);
            } else {
                urls.add(siteDomain + permalink);
            }
        } else {
            String pageSlug = page.getSpec().getSlug();
            if (StringUtils.isNotBlank(pageSlug)) {
                urls.add(siteDomain + pageSlug);
            }
        }

        return urls;
    }
}
