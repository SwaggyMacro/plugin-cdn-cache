package cn.ncii.cdncache;

import cn.ncii.cdncache.entity.CdnProviderConfig;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * CDN 配置设置
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Data
public class CdnSetting {

    public static final String GROUP = "basic";

    /**
     * 是否启用 CDN 缓存刷新
     */
    private Boolean enabled = false;

    /**
     * 站点域名（用于构建刷新 URL）
     */
    private String siteDomain;

    /**
     * CDN 提供商配置列表（支持多个 CDN）
     */
    private List<CdnProviderConfig> providers = new ArrayList<>();

    /**
     * 是否刷新首页
     */
    private Boolean refreshHomePage = true;

    /**
     * 是否刷新文章列表页
     */
    private Boolean refreshArchivePage = true;

    /**
     * 是否刷新分类页
     */
    private Boolean refreshCategoryPage = true;

    /**
     * 是否刷新标签页
     */
    private Boolean refreshTagPage = true;

    /**
     * 评论后是否刷新页面
     */
    private Boolean refreshOnComment = true;

    /**
     * 自定义刷新路径（逗号分隔）
     */
    private String customPaths;

    /**
     * 归档页路由
     */
    private String archiveRoute = "archives";

    /**
     * 分类页路由
     */
    private String categoryRoute = "categories";

    /**
     * 标签页路由
     */
    private String tagRoute = "tags";

    /**
     * 获取所有启用且有效的 CDN 提供商配置
     */
    public List<CdnProviderConfig> getEnabledProviders() {
        if (providers == null) {
            return List.of();
        }
        return providers.stream()
                .filter(p -> Boolean.TRUE.equals(p.getEnabled()) && p.isValid())
                .toList();
    }

    /**
     * CDN 提供商枚举
     */
    public enum CdnProvider {
        ALIYUN,          // 阿里云 CDN
        ALIYUN_ESA,      // 阿里云 ESA（边缘安全加速）
        TENCENT,         // 腾讯云 CDN
        TENCENT_EDGEONE, // 腾讯云 EdgeOne
        CLOUDFLARE,      // Cloudflare
        CUSTOM_PURGE     // 自定义 PURGE 方法
    }
}
