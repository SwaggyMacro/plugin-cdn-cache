package cn.ncii.cdncache.service;

import cn.ncii.cdncache.entity.CdnProviderConfig;
import cn.ncii.cdncache.service.impl.AliyunCdnRefreshService;
import cn.ncii.cdncache.service.impl.AliyunEsaRefreshService;
import cn.ncii.cdncache.service.impl.CloudflareCdnRefreshService;
import cn.ncii.cdncache.service.impl.CustomPurgeCdnRefreshService;
import cn.ncii.cdncache.service.impl.TencentCdnRefreshService;
import cn.ncii.cdncache.service.impl.TencentEdgeOneRefreshService;
import org.springframework.stereotype.Component;

/**
 * CDN 刷新服务工厂
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Component
public class CdnRefreshServiceFactory {

    /**
     * 根据提供商配置创建对应的 CDN 刷新服务
     *
     * @param providerConfig CDN 提供商配置
     * @return CDN 刷新服务实例
     */
    public CdnRefreshService createService(CdnProviderConfig providerConfig) {
        if (providerConfig == null || providerConfig.getProvider() == null) {
            throw new IllegalArgumentException("CDN 提供商配置不能为空");
        }

        return switch (providerConfig.getProvider()) {
            case ALIYUN -> new AliyunCdnRefreshService(providerConfig);
            case ALIYUN_ESA -> new AliyunEsaRefreshService(providerConfig);
            case TENCENT -> new TencentCdnRefreshService(providerConfig);
            case TENCENT_EDGEONE -> new TencentEdgeOneRefreshService(providerConfig);
            case CLOUDFLARE -> new CloudflareCdnRefreshService(providerConfig);
            case CUSTOM_PURGE -> new CustomPurgeCdnRefreshService(providerConfig);
        };
    }
}
