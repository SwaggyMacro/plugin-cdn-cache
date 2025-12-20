package cn.ncii.cdncache;

import cn.ncii.cdncache.entity.RefreshLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.PluginContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdnCachePluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @Test
    void contextLoads() {
        // Mock schemeManager.get() 返回一个 Scheme
        when(schemeManager.get(RefreshLog.class)).thenReturn(
                Scheme.buildFromType(RefreshLog.class)
        );

        CdnCachePlugin plugin = new CdnCachePlugin(context, schemeManager);
        plugin.start();
        plugin.stop();
    }
}
