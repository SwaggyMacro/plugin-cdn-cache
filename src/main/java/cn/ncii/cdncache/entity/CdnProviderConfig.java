package cn.ncii.cdncache.entity;

import cn.ncii.cdncache.CdnSetting;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * CDN 提供商配置
 * 用于存储单个 CDN 提供商的配置信息
 *
 * @author SwaggyMacro
 * @since 1.1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CdnProviderConfig {

    /**
     * 配置名称（用于标识）
     */
    private String name;

    /**
     * 是否启用此配置
     */
    private Boolean enabled = true;

    /**
     * CDN 提供商类型
     */
    private CdnSetting.CdnProvider provider;

    /**
     * Access Key ID（阿里云/腾讯云）
     */
    private String accessKeyId;

    /**
     * Access Key Secret（阿里云/腾讯云）
     */
    private String accessKeySecret;

    /**
     * Cloudflare API Token
     */
    private String cloudflareToken;

    /**
     * Zone ID / Site ID
     * 阿里云 ESA: Site ID
     * 腾讯云 EdgeOne: Zone ID
     * Cloudflare: Zone ID
     */
    private String zoneId;

    /**
     * 自定义 PURGE 请求的 Headers 文本格式
     * 每行一个，格式为 name:value
     */
    private String customHeadersText;

    /**
     * 自定义 PURGE 成功判断关键字
     * 响应体中包含此字符串则认为清除成功
     */
    private String successKeyword;

    /**
     * 解析自定义 Headers 文本为列表
     */
    public List<CustomHeader> getCustomHeaders() {
        List<CustomHeader> headers = new ArrayList<>();
        if (customHeadersText == null || customHeadersText.isBlank()) {
            return headers;
        }
        
        String[] lines = customHeadersText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String headerName = line.substring(0, colonIndex).trim();
                String headerValue = line.substring(colonIndex + 1).trim();
                if (!headerName.isEmpty() && !headerValue.isEmpty()) {
                    headers.add(new CustomHeader(headerName, headerValue));
                }
            }
        }
        return headers;
    }

    /**
     * 检查配置是否有效
     */
    public boolean isValid() {
        if (provider == null) {
            return false;
        }
        
        return switch (provider) {
            case CLOUDFLARE -> cloudflareToken != null && !cloudflareToken.isBlank()
                    && zoneId != null && !zoneId.isBlank();
            case ALIYUN_ESA, TENCENT_EDGEONE -> accessKeyId != null && !accessKeyId.isBlank()
                    && accessKeySecret != null && !accessKeySecret.isBlank()
                    && zoneId != null && !zoneId.isBlank();
            case ALIYUN, TENCENT -> accessKeyId != null && !accessKeyId.isBlank()
                    && accessKeySecret != null && !accessKeySecret.isBlank();
            case CUSTOM_PURGE -> successKeyword != null && !successKeyword.isBlank();
        };
    }

    /**
     * 获取提供商显示名称
     */
    public String getProviderDisplayName() {
        if (provider == null) {
            return "未知";
        }
        return switch (provider) {
            case ALIYUN -> "阿里云 CDN";
            case ALIYUN_ESA -> "阿里云 ESA";
            case TENCENT -> "腾讯云 CDN";
            case TENCENT_EDGEONE -> "腾讯云 EdgeOne";
            case CLOUDFLARE -> "Cloudflare";
            case CUSTOM_PURGE -> "自定义 PURGE";
        };
    }

    /**
     * 自定义 Header 配置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomHeader {
        private String headerName;
        private String headerValue;
    }
}
