package com.example.domain.zsxq.image;

import dev.langchain4j.data.message.ImageContent;

/**
 * 图片 URL → {@link ImageContent} 的转换点。
 *
 * <p>存在的意义是把这个决策<b>收在一处</b>：现在知识星球的图是公开 CDN 地址，
 * 直接交给模型按 URL 拉取即可；将来若出现防盗链、需要带 cookie、或图已下线的情况，
 * 就得改成先下载再转 base64 —— 那时只改实现，调用方（{@link ZsxqImageService}）不动。
 */
@FunctionalInterface
public interface ZsxqImageContentFactory {

    /** @param url 图片原始地址；调用方需保证非空 */
    ImageContent from(String url);
}
