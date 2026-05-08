package cn.ncii.cdncache;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CDN 缓存插件配置类
 * 启用组件扫描以确保所有 @Component 类被正确加载
 */
@Configuration
@EnableScheduling
@ComponentScan(basePackages = "cn.ncii.cdncache")
public class CdnCacheConfiguration {

    public CdnCacheConfiguration() {
        System.out.println("########## CDN-CACHE: CdnCacheConfiguration 已加载 ##########");
    }
}
