package com.example.domain.zsxq.model;

import java.util.List;

/**
 * 一次清洗的结果：保留的题库条目 + 被丢弃的追踪记录。
 * 由 {@link ZsxqCleaningService#run} 返回，CLI 入口负责写盘（question-bank.json / drops.json）。
 */
public record ZsxqCleaningResult(List<ZsxqCleanedDoc> kept, List<ZsxqDropRecord> dropped) {
}
