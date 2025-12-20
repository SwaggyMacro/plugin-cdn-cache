package cn.ncii.cdncache;

import cn.ncii.cdncache.service.CdnRefreshManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论 Reconciler
 * 监听评论审核通过事件，刷新对应文章/页面的 CDN 缓存
 */
@Slf4j
@Component
public class CommentListener implements Reconciler<Reconciler.Request> {

    private final CdnRefreshManager refreshManager;
    private final ExtensionClient client;

    public CommentListener(CdnRefreshManager refreshManager, ExtensionClient client) {
        this.refreshManager = refreshManager;
        this.client = client;
        log.info("CDN-CACHE: CommentListener 已初始化");
    }

    @Override
    public Result reconcile(Request request) {
        String commentName = request.name();

        client.fetch(Comment.class, commentName).ifPresent(comment -> {
            // 只处理已审核通过的评论
            if (comment.getSpec() == null || !Boolean.TRUE.equals(comment.getSpec().getApproved())) {
                return;
            }

            Ref subjectRef = comment.getSpec().getSubjectRef();
            if (subjectRef == null) {
                return;
            }

            String kind = subjectRef.getKind();
            String name = subjectRef.getName();

            if ("Post".equals(kind)) {
                handlePostComment(name);
            } else if ("SinglePage".equals(kind)) {
                handlePageComment(name);
            }
        });

        return Result.doNotRetry();
    }

    private void handlePostComment(String postName) {
        client.fetch(Post.class, postName).ifPresent(post -> {
            log.info("CDN-CACHE: 文章评论审核通过，准备刷新: {}", post.getSpec().getTitle());

            refreshManager.getSettings()
                    .flatMap(setting -> {
                        if (!Boolean.TRUE.equals(setting.getRefreshOnComment())) {
                            log.debug("CDN-CACHE: 评论刷新未启用");
                            return reactor.core.publisher.Mono.empty();
                        }

                        List<String> urls = buildPostUrl(setting, post);
                        return refreshManager.refresh(urls, "COMMENT",
                                post.getMetadata().getName(), post.getSpec().getTitle());
                    })
                    .subscribe(
                            v -> {},
                            e -> log.error("CDN-CACHE: 文章评论刷新失败", e)
                    );
        });
    }

    private void handlePageComment(String pageName) {
        client.fetch(SinglePage.class, pageName).ifPresent(page -> {
            log.info("CDN-CACHE: 页面评论审核通过，准备刷新: {}", page.getSpec().getTitle());

            refreshManager.getSettings()
                    .flatMap(setting -> {
                        if (!Boolean.TRUE.equals(setting.getRefreshOnComment())) {
                            log.debug("CDN-CACHE: 评论刷新未启用");
                            return reactor.core.publisher.Mono.empty();
                        }

                        List<String> urls = buildPageUrl(setting, page);
                        return refreshManager.refresh(urls, "COMMENT",
                                page.getMetadata().getName(), page.getSpec().getTitle());
                    })
                    .subscribe(
                            v -> {},
                            e -> log.error("CDN-CACHE: 页面评论刷新失败", e)
                    );
        });
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new Comment())
                .build();
    }

    private List<String> buildPostUrl(CdnSetting setting, Post post) {
        List<String> urls = new ArrayList<>();
        String siteDomain = setting.getSiteDomain();
        if (StringUtils.isBlank(siteDomain)) return urls;
        if (!siteDomain.endsWith("/")) siteDomain = siteDomain + "/";

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
        }
        return urls;
    }

    private List<String> buildPageUrl(CdnSetting setting, SinglePage page) {
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
        }
        return urls;
    }
}
