package com.example.newmusic.module.recupgrade.config;

import com.example.newmusic.module.recupgrade.config.RecUpgradeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 推荐升级的四个独立线程池（BR-24-013）。
 *
 * <p><b>必须隔离</b>：召回慢不能拖垮精排，精排慢不能拖垮全站业务线程。
 * 因此召回池用 {@link SynchronousQueue} + {@code AbortPolicy}（队列 0 语义）：
 * 池子打满时立刻拒绝该源，由引擎按「单源失败」处理，而不是排队等待。
 *
 * <p>这是模块 15 与模块 08 最大的工程差异之一：模块 08 的推荐是同步调用，
 * 这里所有阶段都有明确的超时边界。
 */
@Configuration
public class RecExecutorConfig {

    @Bean(name = "recallExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor recallExecutor() {
        return new ThreadPoolExecutor(32, 64, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                namedFactory("recall"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "recFeatureExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor recFeatureExecutor() {
        return new ThreadPoolExecutor(16, 32, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(200),
                namedFactory("rec-feature"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "recRankExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor recRankExecutor() {
        return new ThreadPoolExecutor(16, 32, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(200),
                namedFactory("rec-rank"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 离线评估、日报聚合等重任务，单独一个池，避免和在线推荐抢 CPU。 */
    @Bean(name = "recEvalExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor recEvalExecutor() {
        return new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(20),
                namedFactory("rec-eval"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadFactory namedFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, prefix + "-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
