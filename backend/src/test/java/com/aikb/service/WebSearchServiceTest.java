package com.aikb.service;

import com.aikb.model.WebResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联网检索的解析逻辑测试。
 *
 * 只测「解析」和「降级」，不测真实网络：
 *   · 解析是纯函数，用一段固定的 Bing RSS 就能钉死行为
 *   · 网络测试会随外网状态抖动，不适合放进单元测试
 */
class WebSearchServiceTest {

    private final WebSearchService svc = new WebSearchService();

    private static final String RSS = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
            + "<rss version=\"2.0\"><channel><title>必应搜索</title>"
            + "<item>"
            + "<title>死锁 - 维基百科</title>"
            + "<link>https://zh.wikipedia.org/wiki/%E6%AD%BB%E9%94%81</link>"
            + "<description>死锁是&lt;b&gt;两个或多个进程&lt;/b&gt;互相等待对方资源 &amp; 调度失衡的现象</description>"
            + "</item>"
            + "<item>"
            + "<title>死锁的四个必要条件</title>"
            + "<link>https://example.com/deadlock</link>"
            + "<description><![CDATA[互斥、占有并等待、不可剥夺、循环等待]]></description>"
            + "</item>"
            + "<item>"
            + "<title></title>"
            + "<link>https://example.com/empty</link>"
            + "<description>标题为空应被丢弃</description>"
            + "</item>"
            + "</channel></rss>";

    @Test
    void 能解析出RSS条目() {
        List<WebResult> list = svc.parse(RSS);
        assertEquals(2, list.size(), "空标题的那条应被丢掉");
        assertEquals("死锁 - 维基百科", list.get(0).getTitle());
        assertTrue(list.get(0).getUrl().startsWith("https://zh.wikipedia.org/"));
        assertEquals("死锁的四个必要条件", list.get(1).getTitle());
    }

    @Test
    void 解码实体并去掉HTML标签() {
        List<WebResult> list = svc.parse(RSS);
        String d = list.get(0).getSnippet();
        assertFalse(d.contains("<b>"), "标签应被去掉，实际: " + d);
        assertTrue(d.contains("两个或多个进程"), "正文应保留，实际: " + d);
        assertTrue(d.contains("&"), "&amp; 应解码成 &，实际: " + d);
        assertFalse(d.contains("&amp;"), "不该残留实体，实际: " + d);
    }

    @Test
    void 支持CDATA() {
        List<WebResult> list = svc.parse(RSS);
        assertTrue(list.get(1).getSnippet().contains("互斥"), "CDATA 内容应保留");
        assertFalse(list.get(1).getSnippet().contains("CDATA"));
    }

    @Test
    void 过长摘要要截断() {
        StringBuilder longDesc = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longDesc.append("字");
        }
        String xml = "<rss><channel><item><title>标题</title><link>https://a.b/c</link>"
                + "<description>" + longDesc + "</description></item></channel></rss>";
        List<WebResult> list = svc.parse(xml);
        assertEquals(1, list.size());
        assertTrue(list.get(0).getSnippet().length() <= 161,
                "摘要应截断到 160 字左右，实际: " + list.get(0).getSnippet().length());
        assertTrue(list.get(0).getSnippet().endsWith("…"));
    }

    @Test
    void 非法输入不抛异常() {
        assertTrue(svc.parse(null).isEmpty());
        assertTrue(svc.parse("").isEmpty());
        assertTrue(svc.parse("不是 XML").isEmpty());
    }

    @Test
    void 关闭时搜索直接返回空_不打网络() {
        // 没经过 Spring 注入时 enabled 默认 false：必须安全降级，不能抛异常
        assertFalse(svc.isEnabled());
        assertTrue(svc.search("死锁", 5).isEmpty());
        assertTrue(svc.search(null, 5).isEmpty());
    }
}
