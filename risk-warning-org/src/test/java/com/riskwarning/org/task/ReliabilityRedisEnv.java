package com.riskwarning.org.task;

import com.riskwarning.common.utils.RedisUtil;
import org.junit.jupiter.api.AfterAll;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R1-03 真实 Redis 隔离环境（org 模块副本）。
 * 使用独立 DB 与测试 key 前缀；与生产一致的默认 RedisTemplate（JDK 序列化）。
 */
public final class ReliabilityRedisEnv {

    private final LettuceConnectionFactory connectionFactory;
    private final RedisTemplate<String, Object> redisTemplate;

    private ReliabilityRedisEnv(LettuceConnectionFactory connectionFactory,
                                RedisTemplate<String, Object> redisTemplate) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
    }

    public static ReliabilityRedisEnv start() {
        String host = envOr("R1_03_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOr("R1_03_REDIS_PORT", "6379"));
        String password = envOr("R1_03_REDIS_PASSWORD", "123456");
        int database = Integer.parseInt(envOr("R1_03_REDIS_DATABASE", "15"));

        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.setPassword(password);
        factory.setDatabase(database);
        factory.afterPropertiesSet();

        // 与生产自动装配一致：默认 JDK 序列化
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return new ReliabilityRedisEnv(factory, template);
    }

    public RedisUtil redisUtil() {
        RedisUtil redisUtil = new RedisUtil();
        ReflectionTestUtils.setField(redisUtil, "redisTemplate", redisTemplate);
        return redisUtil;
    }

    public RedisTemplate<String, Object> getRedisTemplate() { return redisTemplate; }

    /** 只清理本测试前缀的 key。 */
    public void deleteByPrefix(String prefix) {
        java.util.Set<String> keys = redisTemplate.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    public void stop() {
        connectionFactory.destroy();
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
