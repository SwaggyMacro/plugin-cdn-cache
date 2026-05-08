package cn.ncii.cdncache.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ScheduledUriRefreshTaskTest {

    @Mock
    CdnRefreshManager refreshManager;

    @Test
    void shouldExtractLocUrlsFromSitemap() {
        ScheduledUriRefreshTask task = new ScheduledUriRefreshTask(refreshManager);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://example.com/</loc></url>
                  <url><loc>https://example.com/archives/a-post</loc></url>
                </urlset>
                """;

        List<String> urls = task.extractLocUrlsFromSitemap(xml);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://example.com/"));
        assertTrue(urls.contains("https://example.com/archives/a-post"));
    }

    @Test
    void shouldExcludeConsoleAndApiWhenNoDirectoryFilter() {
        ScheduledUriRefreshTask task = new ScheduledUriRefreshTask(refreshManager);

        List<String> urls = task.filterByDirectories(List.of(
                "https://example.com/",
                "https://example.com/archives/a-post",
                "https://example.com/console/dashboard",
                "https://example.com/api/v1/posts"
        ), null);

        assertEquals(List.of(
                "https://example.com/",
                "https://example.com/archives/a-post"
        ), urls);
    }

    @Test
    void shouldFilterByConfiguredDirectories() {
        ScheduledUriRefreshTask task = new ScheduledUriRefreshTask(refreshManager);

        List<String> urls = task.filterByDirectories(List.of(
                "https://example.com/",
                "https://example.com/archives/a-post",
                "https://example.com/categories/java",
                "https://example.com/about"
        ), "/archives,/categories");

        assertEquals(List.of(
                "https://example.com/archives/a-post",
                "https://example.com/categories/java"
        ), urls);
    }
}
