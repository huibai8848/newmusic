package com.example.newmusic.module.ops.datasource;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * {@link Slave} 的织入实现。
 *
 * <p>用「保存 / 恢复」而不是「进入设、退出清」：被标注的方法可能被另一个同线程方法调用，
 * 直接 {@code clear()} 会把外层已有的主库标记也一起抹掉（例如外层刚写完要读主）。
 */
@Slf4j
@Aspect
@Component
public class DataSourceAspect {

    @Around("@annotation(com.example.newmusic.module.ops.datasource.Slave)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        DataSourceType previous = DataSourceContext.get();
        if (!DataSourceContext.isWriteAfterRead()) {
            DataSourceContext.set(DataSourceType.SLAVE);
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (previous == null) {
                DataSourceContext.clear();
            } else {
                DataSourceContext.set(previous);
            }
        }
    }
}
