package cn.ncii.cdncache;

import lombok.Data;

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
     * CDN 提供商类型
     */
    private CdnProvider provider = CdnProvider.ALIYUN;

    /**
     * Access Key ID
     */
    private String accessKeyId;

    /**
     * Access Key Secret
     */
    private String accessKeySecret;

    /**
     * 站点域名（用于构建刷新 URL）
     */
    private String siteDomain;

    /**
     * 是否启用
     */
    private Boolean enabled = false;

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
     * Zone ID（EdgeOne/Cloudflare/ESA 需要）
     */
    private String zoneId;

    /**
     * Cloudflare API Token
     */
    private String cloudflareToken;

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
     * CDN 提供商枚举
     */
    public enum CdnProvider {
        ALIYUN,          // 阿里云 CDN
        ALIYUN_ESA,      // 阿里云 ESA（边缘安全加速）
        TENCENT,         // 腾讯云 CDN
        TENCENT_EDGEONE, // 腾讯云 EdgeOne
        CLOUDFLARE       // Cloudflare
    }
}
