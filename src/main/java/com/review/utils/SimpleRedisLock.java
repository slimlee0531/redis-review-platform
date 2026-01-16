package com.review.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private final StringRedisTemplate stringRedisTemplate;
    private final String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    // 集群模式下区分服务器
    private static final String NODE_ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    // lua 脚本调用
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 1. 获取线程标识
        String threadId = NODE_ID_PREFIX + Thread.currentThread().getId();
        // 2. 获取锁
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unlock() {
        // 调用 lua 脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                NODE_ID_PREFIX + Thread.currentThread().getId()
        );
    }

/*    @Override
    public void unlock() {
        // 获取线程标识 (区分服务器、线程)
        String threadId = NODE_ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 若标识一致，释放锁
        if (threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/

}
