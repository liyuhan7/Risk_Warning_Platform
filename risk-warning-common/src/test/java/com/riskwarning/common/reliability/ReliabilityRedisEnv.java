package com.riskwarning.common.reliability;

import com.riskwarning.common.utils.RedisUtil;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R1-03 真实 Redis 隔离环境。
 * 使用独立 database 与测试 key 前缀；与生产一致的默认 RedisTemplate
 * （JDK 序列化）保证可靠队列行为与线上路径相同。
 */
public final class ReliabilityRedisEnv {

    private final LettuceConnectionFactory connectionFactory;
    private final RedisTemplate<String, Object> redisTemplate;

    private ReliabilityRedisEnv(LettuceConnectionFactory connectionFactory,
                                RedisTemplate<String, Object> redisTemplate) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
    }

    /** 连接独立测试 DB；密码默认与 build/docker-compose.yml 一致。 */
    public static ReliabilityRedisEnv start() {
        String host = envOr("R1_03_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOr("R1_03_REDIS_PORT", "6379"));
        String password = envOr("R1_03_REDIS_PASSWORD", "123456");
        int database = Integer.parseInt(envOr("R1_03_REDIS_DATABASE", "15"));

        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.setPassword(password);
        factory.setDatabase(database);
        factory.afterPropertiesSet();

        // 与生产自动装配保持一致：未显式配置序列化器时使用 JDK 序列化
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return new ReliabilityRedisEnv(factory, template);
    }

    /** 与生产 @Resource 注入路径一致的 RedisUtil。 */
    public RedisUtil redisUtil() {
        RedisUtil redisUtil = new RedisUtil();
        ReflectionTestUtils.setField(redisUtil, "redisTemplate", redisTemplate);
        return redisUtil;
    }

    public RedisTemplate<String, Object> getRedisTemplate() { return redisTemplate; }

    /** 只清理本测试前缀的 key，不动其他数据。 */
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
