package com.example.newmusic.module.search.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务（BR-15-031 的热搜整点落库）。
 *
 * <p><b>这个开关放在搜索模块里是要还的技术债。</b>PRD 的目标调度平台是 XXL-Job，
 * 模块 03 的 T317 已经登记过同一个问题（当时的定时任务是榜单/计数落库）。
 * 一旦引入调度平台，本类连同 {@code HotSearchService.persistDaily()} 里的
 * {@code @Scheduled} 一起迁走。
 *
 * <p>为什么不把它加到 {@code NewmusicApplication}：那会让「本项目开了定时任务」
 * 变成一个全局事实，而当前只有一个消费者。放在这里，将来要连同搜索模块一起摘掉时
 * 改动范围是清楚的。
 */
@Configuration
@EnableScheduling
public class SearchSchedulingConfig {
}
