package cn.ncii.cdncache;

import cn.ncii.cdncache.entity.RefreshLog;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * CDN 缓存刷新插件
 * 在文章发布时自动刷新相关页面的 CDN 缓存
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Component
public class CdnCachePlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public CdnCachePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(RefreshLog.class);
        System.out.println("##################################################");
        System.out.println("########## CDN-CACHE 插件启动成功 v1.0.1 ##########");
        System.out.println("##################################################");
    }

    @Override
    public void stop() {
        schemeManager.unregister(schemeManager.get(RefreshLog.class));
        System.out.println("########## CDN-CACHE 插件已停止 ##########");
    }
}
