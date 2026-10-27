package com.example.newmusic.module.search.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 搜索的异步线程池（系分 D-43）。
 *
 * <p>承担两件事，都由 BR-15-006/007 要求「不阻塞搜索响应」：写搜索历史、累加热搜计数。
 * 这两件事的代价是各一次 Redis 写（外加历史还有一次 DB insert），
 * 让用户为一次统计付 5~20ms 的等待是不划算的。
 *
 * <p><b>为什么不用 {@code @EnableAsync}</b>：那是个全局开关。项目里一旦有人把
 * {@code @Async} 标在任何地方，所有异步任务就共用同一个默认线程池——搜索统计、
 * 邮件、报表全挤在一起，任何一处阻塞都会让另一处排队。显式线程池的好处是
 * 队列有界、拒绝策略明确、{@code shutdown()} 由容器调用、测试里能等待收敛。
 *
 * <p><b>{@code CallerRunsPolicy} 是刻意的。</b>队列满了就让**调用方线程自己跑**，
 * 即那一个搜索请求慢几毫秒。替代方案是丢弃（{@code DiscardPolicy}）：
 * 搜索历史与热搜是统计型数据，丢一条的后果是「数据看着对，就是少了一点」，
 * 这类故障没有报错、没有日志、只能靠对账发现，比慢几毫秒贵得多。
 */
@Configuration
public class SearchAsyncConfig {

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor searchExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "search-async-" + seq.incrementAndGet());
                // 守护线程：应用关闭时不该因为还剩几条统计没写完而卡住
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1000),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        // 核心线程不回收：搜索是持续流量，让核心线程常驻省掉反复创建的开销
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
