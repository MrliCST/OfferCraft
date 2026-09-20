package com.example.domain.zsxq.crawl.api;

import java.util.ArrayList;
import java.util.List;

/** 星主长文的全文：标题 + 正文 Markdown + 正文图 URL。 */
public class ZsxqArticle {

    public String title;
    public String markdown;
    public List<String> imageUrls = new ArrayList<>();

    public ZsxqArticle() {
    }

    public ZsxqArticle(String title, String markdown, List<String> imageUrls) {
        this.title = title;
        this.markdown = markdown;
        this.imageUrls = imageUrls == null ? new ArrayList<>() : imageUrls;
    }
}
