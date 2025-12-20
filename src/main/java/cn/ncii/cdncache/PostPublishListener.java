package cn.ncii.cdncache;

import cn.ncii.cdncache.service.CdnRefreshManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.event.post.PostUpdatedEvent;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章更新事件监听器
 */
@Slf4j
@Component
public class PostPublishListener implements ApplicationListener<PostUpdatedEvent> {

    private final CdnRefreshManager refreshManager;
    private final ReactiveExtensionClient client;

    public PostPublishListener(CdnRefreshManager refreshManager, ReactiveExtensionClient client) {
        this.refreshManager = refreshManager;
        this.client = client;
        log.info("CDN-CACHE: PostPublishListener 已初始化");
    }

    @Override
    public void onApplicationEvent(PostUpdatedEvent event) {
        log.info("CDN-CACHE: 收到 PostUpdatedEvent, postName = {}", event.getName());

        String postName = event.getName();

        client.get(Post.class, postName)
                .flatMap(post -> {
                    if (!isPublished(post)) {
                        log.debug("CDN-CACHE: 文章未发布，跳过");
                        return reactor.core.publisher.Mono.empty();
                    }

                    log.info("CDN-CACHE: 文章已发布: {}", post.getSpec().getTitle());
                    
                    return refreshManager.getSettings()
                            .flatMap(setting -> {
                                List<String> urls = buildRefreshUrls(setting, post);
                                return refreshManager.refresh(urls, "POST_UPDATE", 
                                        post.getMetadata().getName(), post.getSpec().getTitle());
                            });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> {},
                        error -> log.error("CDN-CACHE: 处理失败", error)
                );
    }

    private boolean isPublished(Post post) {
        Post.PostStatus status = post.getStatus();
        if (status == null) return false;
        return Post.PostPhase.PUBLISHED.name().equals(status.getPhase());
    }

    private List<String> buildRefreshUrls(CdnSetting setting, Post post) {
        List<String> urls = new ArrayList<>();
        String siteDomain = setting.getSiteDomain();
        if (StringUtils.isBlank(siteDomain)) return urls;
        if (!siteDomain.endsWith("/")) siteDomain = siteDomain + "/";

        // 优先使用 Post 的 permalink
        Post.PostStatus status = post.getStatus();
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
            String postSlug = post.getSpec().getSlug();
            if (StringUtils.isNotBlank(postSlug)) {
                urls.add(siteDomain + "archives/" + postSlug);
            }
        }

        String archiveRoute = StringUtils.defaultIfBlank(setting.getArchiveRoute(), "archives");
        String categoryRoute = StringUtils.defaultIfBlank(setting.getCategoryRoute(), "categories");
        String tagRoute = StringUtils.defaultIfBlank(setting.getTagRoute(), "tags");

        if (Boolean.TRUE.equals(setting.getRefreshHomePage())) {
            urls.add(siteDomain);
        }
        if (Boolean.TRUE.equals(setting.getRefreshArchivePage())) {
            urls.add(siteDomain + archiveRoute);
        }
        if (Boolean.TRUE.equals(setting.getRefreshCategoryPage())) {
            urls.add(siteDomain + categoryRoute);
        }
        if (Boolean.TRUE.equals(setting.getRefreshTagPage())) {
            urls.add(siteDomain + tagRoute);
        }

        if (StringUtils.isNotBlank(setting.getCustomPaths())) {
            String[] customPaths = setting.getCustomPaths().split(",");
            for (String path : customPaths) {
                path = path.trim();
                if (StringUtils.isNotBlank(path)) {
                    if (path.startsWith("/")) {
                        urls.add(siteDomain.substring(0, siteDomain.length() - 1) + path);
                    } else {
                        urls.add(siteDomain + path);
                    }
                }
            }
        }

        return urls;
    }
}
