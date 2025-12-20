package cn.ncii.cdncache.service;

import cn.ncii.cdncache.CdnSetting;
import cn.ncii.cdncache.service.impl.AliyunCdnRefreshService;
import cn.ncii.cdncache.service.impl.AliyunEsaRefreshService;
import cn.ncii.cdncache.service.impl.CloudflareCdnRefreshService;
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
     * 根据配置创建对应的 CDN 刷新服务
     *
     * @param setting CDN 配置
     * @return CDN 刷新服务实例
     */
    public CdnRefreshService createService(CdnSetting setting) {
        if (setting == null || setting.getProvider() == null) {
            throw new IllegalArgumentException("CDN 配置或提供商不能为空");
        }

        return switch (setting.getProvider()) {
            case ALIYUN -> new AliyunCdnRefreshService(setting);
            case ALIYUN_ESA -> new AliyunEsaRefreshService(setting);
            case TENCENT -> new TencentCdnRefreshService(setting);
            case TENCENT_EDGEONE -> new TencentEdgeOneRefreshService(setting);
            case CLOUDFLARE -> new CloudflareCdnRefreshService(setting);
        };
    }
}
