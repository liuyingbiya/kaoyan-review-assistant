package com.aikb.service;

import com.aikb.model.WebResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网检索：题库没命中时，去网上找一条能看的答案。
 *
 * 为什么用 Bing 的 RSS 而不是搜索 API：
 *   · 不用申请 key（Tavily / Serper / 必应搜索 API 都要注册，多半还要绑卡）
 *   · RSS 是结构化 XML，比抓 HTML 稳（页面改版我们不用跟着改选择器）
 *   · 服务器在国内，cn.bing.com 可以直连
 *
 * 它给的是什么（说清楚，免得期望错位）：
 *   只有「标题 + 摘要 + 链接」，没有推理、没有整合。
 *   所以这里的定位是「检索结果展示」，不是「AI 回答」——真正的组织语言要靠大模型。
 *
 * 可靠性设计：
 *   · 5 秒超时，失败就返回空列表（绝不能因为联网失败让整个问答挂掉）
 *   · 10 分钟内存缓存，同一个问题重复问不再打网络
 *   · 缓存最多 300 条，超过就清空重建（简单但不会泄漏内存）
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    /** 多个入口依次尝试；第一个通了就不再试 */
    private static final String[] ENDPOINTS = {
            "https://cn.bing.com/search?q=%s&format=rss&count=10",
            "https://www.bing.com/search?q=%s&format=rss&count=10",
    };

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern DESC = Pattern.compile("<description>(.*?)</description>", Pattern.DOTALL);

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${web.search.enabled:true}")
    private boolean enabled;

    @Value("${web.search.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${web.search.cache-minutes:10}")
    private int cacheMinutes;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 检索网页。
     *
     * @return 结果列表；任何失败都返回空列表，不抛异常
     */
    public List<WebResult> search(String question, int limit) {
        if (!enabled || question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String key = buildQuery(question);
        if (key.isEmpty()) {
            return new ArrayList<>();
        }
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.expired(cacheMinutes)) {
            return limit(cached.results, limit);
        }

        List<WebResult> results = new ArrayList<>();
        for (String tpl : ENDPOINTS) {
            try {
                String body = fetch(String.format(tpl, URLEncoder.encode(key, "UTF-8")));
                if (body != null) {
                    results = parse(body);
                }
                if (!results.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                log.debug("联网检索失败 endpoint={} err={}", tpl, e.toString());
            }
        }

        if (results.isEmpty()) {
            log.info("联网检索无结果: {}", key);
        } else {
            log.info("联网检索命中 {} 条: {}", results.size(), key);
            if (cache.size() > 300) {
                cache.clear();
            }
            cache.put(key, new CacheEntry(results));
        }
        return limit(results, limit);
    }

    /**
     * 把自然语言问句变成搜索引擎能用的关键词。
     *
     * 为什么必须处理：直接拿整句去搜，「2026年考研报名时间是什么时候？」
     * 会被 Bing 拆成以「2026年」为主的查询，返回「2026年_百度百科」这种无关结果。
     * 去掉疑问词和标点后，「2026年考研报名时间」才是有区分度的关键词。
     */
    String buildQuery(String question) {
        String s = question;
        // 注意：正则备选是「从左到右」匹配的，而且匹配起点在「是」这个字上。
        // 所以必须把「是什么时候」整体写在「是什么」前面，否则先命中「是什么」，
        // 剩下一个「时候」混进关键词里。
        s = s.replaceAll(
                "(是什么时候|什么时候|是多少|是什么|有哪几种|是哪几种|有哪些|怎么办|怎么样|怎么|如何|为什么|请问|帮我|一下)", " ");
        s = s.replaceAll("[？?。！!，,；;：:、\"'“”‘’（）()]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        // 关键词太少（比如只剩「考研」两个字）就别浪费一次网络请求
        return s.length() >= 2 ? s : "";
    }

    // ============================================================
    // 网络
    // ============================================================
    private String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(true);
        // 不带 UA 会被 Bing 直接拒绝
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        int code = conn.getResponseCode();
        if (code != 200) {
            log.debug("联网检索 HTTP {}", code);
            return null;
        }
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    // ============================================================
    // 解析（独立出来，便于单元测试，不需要网络）
    // ============================================================
    List<WebResult> parse(String xml) {
        List<WebResult> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        Matcher items = ITEM.matcher(xml);
        while (items.find()) {
            String block = items.group(1);
            String title = clean(tag(TITLE, block));
            String link = clean(tag(LINK, block));
            String desc = clean(tag(DESC, block));

            if (title.isEmpty() || link.isEmpty()) {
                continue;
            }
            if (desc.length() > 160) {
                desc = desc.substring(0, 160) + "…";
            }
            out.add(new WebResult(title, link, desc));
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    private String tag(Pattern p, String block) {
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1) : "";
    }

    /** 去 CDATA、解实体、去标签、压空白 */
    String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("<![CDATA[")) {
            s = s.substring(9);
            if (s.endsWith("]]>")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        // 顺序很重要：RSS 里的 HTML 是转义过的（&lt;b&gt;），
        // 必须先解实体，标签才会露出来，然后再去标签。
        // 反过来做的话 &lt;b&gt; 会被当成普通文字留下来。
        s = unescape(s);
        s = s.replaceAll("<[^>]+>", " ");
        s = unescape(s);            // 处理双重转义（&amp;lt;）
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private List<WebResult> limit(List<WebResult> list, int n) {
        if (list == null) {
            return new ArrayList<>();
        }
        return list.size() <= n ? list : new ArrayList<>(list.subList(0, n));
    }

    private static class CacheEntry {
        final List<WebResult> results;
        final long at = System.currentTimeMillis();

        CacheEntry(List<WebResult> results) {
            this.results = results;
        }

        boolean expired(int minutes) {
            return System.currentTimeMillis() - at > minutes * 60_000L;
        }
    }
}
