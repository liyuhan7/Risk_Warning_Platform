package com.riskwarning.common.utils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.reliability.redis.RedisClaimDeserializationException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;

/**
 *
 * @author 王赛超 基于spring和redis的redisTemplate工具类 针对所有的hash 都是以h开头的方法 针对所有的Set
 *         都是以s开头的方法 不含通用方法 针对所有的List 都是以l开头的方法
 */
@Component
@Slf4j
public class RedisUtil {

    private static final byte[] CLAIM_SCRIPT = ("local item = redis.call('LINDEX', KEYS[2], 0); "
            + "if item then return item end; "
            + "return redis.call('RPOPLPUSH', KEYS[1], KEYS[2]);")
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] DEAD_LETTER_SCRIPT = ("local removed = redis.call('LREM', KEYS[1], 1, ARGV[1]); "
            + "if removed == 1 then redis.call('LPUSH', KEYS[2], ARGV[2]); end; "
            + "return removed;")
            .getBytes(StandardCharsets.UTF_8);

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 优先恢复 PROCESSING 中未确认的任务，否则原子地从 READY 领取一条。
     */
    public <T> ClaimedRedisItem<T> claimListItem(String readyKey, String pendingKey,
            Class<T> valueType) {
        requireQueueKey(readyKey, "readyKey");
        requireQueueKey(pendingKey, "pendingKey");
        if (valueType == null) {
            throw new IllegalArgumentException("valueType 不能为空");
        }
        byte[] ready = serializeKey(readyKey);
        byte[] pending = serializeKey(pendingKey);
        byte[] raw = redisTemplate.execute((RedisCallback<byte[]>) connection -> connection.eval(CLAIM_SCRIPT,
                ReturnType.VALUE, 2, ready, pending));
        if (raw == null) {
            return null;
        }
        try {
            Object value = valueSerializer().deserialize(raw);
            if (!valueType.isInstance(value)) {
                throw new IllegalStateException("Redis 任务类型不是 " + valueType.getName());
            }
            return new ClaimedRedisItem<>(valueType.cast(value), raw, readyKey, pendingKey);
        } catch (RuntimeException exception) {
            throw new RedisClaimDeserializationException(readyKey, pendingKey, raw, exception);
        }
    }

    /** 使用领取时保存的原始 value 字节确认任务。 */
    public void acknowledgeListItem(ClaimedRedisItem<?> claimed) {
        if (claimed == null) {
            throw new IllegalArgumentException("claimed 不能为空");
        }
        Long removed = redisTemplate.execute((RedisCallback<Long>) connection -> connection
                .lRem(serializeKey(claimed.getPendingKey()), 1, claimed.getRawValue()));
        if (removed == null || removed != 1L) {
            throw new IllegalStateException("Redis 待处理任务 ACK 失败: " + claimed.getPendingKey());
        }
    }

    /**
     * 将无法反序列化的原始任务从 PROCESSING 原子移动到 DEAD。
     */
    public void deadLetterListItem(String pendingKey, String deadKey, byte[] rawValue,
            Object deadLetterValue) {
        requireQueueKey(pendingKey, "pendingKey");
        requireQueueKey(deadKey, "deadKey");
        if (rawValue == null || rawValue.length == 0 || deadLetterValue == null) {
            throw new IllegalArgumentException("rawValue 和 deadLetterValue 不能为空");
        }
        byte[] serializedDeadLetter = valueSerializer().serialize(deadLetterValue);
        if (serializedDeadLetter == null) {
            throw new IllegalStateException("Redis dead letter 序列化失败");
        }
        Long moved = redisTemplate
                .execute((RedisCallback<Long>) connection -> connection.eval(DEAD_LETTER_SCRIPT, ReturnType.INTEGER, 2,
                        serializeKey(pendingKey), serializeKey(deadKey),
                        rawValue, serializedDeadLetter));
        if (moved == null || moved != 1L) {
            throw new IllegalStateException("Redis poison task 移入 DEAD 失败: " + pendingKey);
        }
    }

    @SuppressWarnings("unchecked")
    private RedisSerializer<Object> valueSerializer() {
        RedisSerializer<?> serializer = redisTemplate.getValueSerializer();
        if (serializer == null) {
            throw new IllegalStateException("RedisTemplate valueSerializer 未配置");
        }
        return (RedisSerializer<Object>) serializer;
    }

    @SuppressWarnings("unchecked")
    private byte[] serializeKey(String key) {
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) redisTemplate.getKeySerializer();
        if (serializer == null) {
            throw new IllegalStateException("RedisTemplate keySerializer 未配置");
        }
        byte[] bytes = serializer.serialize(key);
        if (bytes == null) {
            throw new IllegalStateException("Redis key 序列化失败: " + key);
        }
        return bytes;
    }

    private void requireQueueKey(String key, String name) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    // =============================common============================
    /**
     * 指定缓存失效时间
     *
     * @param key
     *             键
     * @param time
     *             时间(秒)
     *
     */
    public boolean expire(String key, long time) {
        try {
            if (time > 0) {
                redisTemplate.expire(key, time, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 根据key 获取过期时间
     *
     * @param key
     *            键 不能为null
     *            时间(秒) 返回0代表为永久有效
     */
    public long getExpire(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * 判断key是否存在
     *
     * @param key
     *            键
     *            true 存在 false不存在
     */
    public boolean hasKey(String key) {
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 删除缓存
     *
     * @param key
     *            可以传一个值 或多个
     */
    @SuppressWarnings("unchecked")
    public void del(String... key) {
        if (key != null && key.length > 0) {
            if (key.length == 1) {
                redisTemplate.delete(key[0]);
            } else {
                redisTemplate.delete((Collection<String>) CollectionUtils.arrayToList(key));
            }
        }
    }

    /**
     * 自增计数并为键设置过期时间，返回自增后的值。
     * 用于毒丸/移交失败的尝试上限判定；每次调用都续期，保证计数键最终可回收。
     */
    public long incrementWithExpire(String key, long expireSeconds) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        Long value = redisTemplate.opsForValue().increment(key);
        if (value == null) {
            throw new IllegalStateException("Redis 计数自增失败: " + key);
        }
        expire(key, expireSeconds);
        return value;
    }

    // ============================String=============================
    /**
     * 普通缓存获取
     *
     * @param key
     *            键
     *            值
     */
    public Object get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(key);
    }

    /**
     * 普通缓存放入
     *
     * @param key
     *              键
     * @param value
     *              值
     *              true成功 false失败
     */
    public boolean set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }

    }

    /**
     * 普通缓存放入并设置时间
     *
     * @param key
     *              键
     * @param value
     *              值
     * @param time
     *              时间(秒) time要大于0 如果time小于等于0 将设置无限期
     *              true成功 false 失败
     */
    public boolean set(String key, Object value, long time) {
        try {
            if (time > 0) {
                redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
            } else {
                set(key, value);
            }
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 递增 适用场景： https://blog.csdn.net/y_y_y_k_k_k_k/article/details/79218254
     * 高并发生成订单号，秒杀类的业务逻辑等。。
     *
     *
     */
    public long incr(String key, long delta) {
        if (delta < 0) {
            throw new RuntimeException("递增因子必须大于0");
        }
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 递减
     *
     * @param key
     *
     */
    public long decr(String key, long delta) {
        if (delta < 0) {
            throw new RuntimeException("递减因子必须大于0");
        }
        return redisTemplate.opsForValue().increment(key, -delta);
    }

    // ================================Map=================================
    /**
     * HashGet
     *
     * @param key
     *             键 不能为null
     * @param item
     *             项 不能为null
     *             值
     */
    public Object hget(String key, String item) {
        return redisTemplate.opsForHash().get(key, item);
    }

    /**
     * 获取hashKey对应的所有键值
     *
     * @param key
     *            键
     *            对应的多个键值
     */
    public Map<Object, Object> hmget(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /**
     * HashSet
     *
     * @param key
     *            键
     * @param map
     *            对应多个键值
     *            true 成功 false 失败
     */
    public boolean hmset(String key, Map<String, Object> map) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * HashSet 并设置时间
     *
     * @param key
     *             键
     * @param map
     *             对应多个键值
     * @param time
     *             时间(秒)
     *             true成功 false失败
     */
    public boolean hmset(String key, Map<String, Object> map, long time) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
            if (time > 0) {
                expire(key, time);
            }
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 向一张hash表中放入数据,如果不存在将创建
     *
     * @param key
     *              键
     * @param item
     *              项
     * @param value
     *              值
     *              true 成功 false失败
     */
    public boolean hset(String key, String item, Object value) {
        try {
            redisTemplate.opsForHash().put(key, item, value);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 向一张hash表中放入数据,如果不存在将创建
     *
     * @param key
     *              键
     * @param item
     *              项
     * @param value
     *              值
     * @param time
     *              时间(秒) 注意:如果已存在的hash表有时间,这里将会替换原有的时间
     *              true 成功 false失败
     */
    public boolean hset(String key, String item, Object value, long time) {
        try {
            redisTemplate.opsForHash().put(key, item, value);
            if (time > 0) {
                expire(key, time);
            }
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 删除hash表中的值
     *
     * @param key
     *             键 不能为null
     * @param item
     *             项 可以使多个 不能为null
     */
    public void hdel(String key, Object... item) {
        redisTemplate.opsForHash().delete(key, item);
    }

    /**
     * 判断hash表中是否有该项的值
     *
     * @param key
     *             键 不能为null
     * @param item
     *             项 不能为null
     *             true 存在 false不存在
     */
    public boolean hHasKey(String key, String item) {
        return redisTemplate.opsForHash().hasKey(key, item);
    }

    /**
     * hash递增 如果不存在,就会创建一个 并把新增后的值返回
     *
     * @param key
     *             键
     * @param item
     *             项
     * @param by
     *             要增加几(大于0)
     *
     */
    public double hincr(String key, String item, double by) {
        return redisTemplate.opsForHash().increment(key, item, by);
    }

    /**
     * hash递减
     *
     * @param key
     *             键
     * @param item
     *             项
     * @param by
     *             要减少记(小于0)
     *
     */
    public double hdecr(String key, String item, double by) {
        return redisTemplate.opsForHash().increment(key, item, -by);
    }

    // ============================set=============================
    /**
     * 根据key获取Set中的所有值
     *
     * @param key
     *            键
     *
     */
    public Set<Object> sGet(String key) {
        try {
            return redisTemplate.opsForSet().members(key);
        } catch (Exception e) {
            log.error(key, e);
            return null;
        }
    }

    /**
     * 根据value从一个set中查询,是否存在
     *
     * @param key
     *              键
     * @param value
     *              值
     *              true 存在 false不存在
     */
    public boolean sHasKey(String key, Object value) {
        try {
            return redisTemplate.opsForSet().isMember(key, value);
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 将数据放入set缓存
     *
     * @param key
     *               键
     * @param values
     *               值 可以是多个
     *               成功个数
     */
    public long sSet(String key, Object... values) {
        try {
            return redisTemplate.opsForSet().add(key, values);
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    /**
     * 将set数据放入缓存
     *
     * @param key
     *               键
     * @param time
     *               时间(秒)
     * @param values
     *               值 可以是多个
     *               成功个数
     */
    public long sSetAndTime(String key, long time, Object... values) {
        try {
            Long count = redisTemplate.opsForSet().add(key, values);
            if (time > 0)
                expire(key, time);
            return count;
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    /**
     * 获取set缓存的长度
     *
     * @param key
     *            键
     *
     */
    public long sGetSetSize(String key) {
        try {
            return redisTemplate.opsForSet().size(key);
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    /**
     * 移除值为value的
     *
     * @param key
     *               键
     * @param values
     *               值 可以是多个
     *               移除的个数
     */
    public long setRemove(String key, Object... values) {
        try {
            Long count = redisTemplate.opsForSet().remove(key, values);
            return count;
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    // ============================zset=============================
    /**
     * 根据key获取Set中的所有值
     *
     * @param key
     *            键
     *
     */
    public Set<Object> zSGet(String key) {
        try {
            return redisTemplate.opsForSet().members(key);
        } catch (Exception e) {
            log.error(key, e);
            return null;
        }
    }

    /**
     * 根据value从一个set中查询,是否存在
     *
     * @param key
     *              键
     * @param value
     *              值
     *              true 存在 false不存在
     */
    public boolean zSHasKey(String key, Object value) {
        try {
            return redisTemplate.opsForSet().isMember(key, value);
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    public Boolean zSSet(String key, Object value, double score) {
        try {
            return redisTemplate.opsForZSet().add(key, value, 2);
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 将set数据放入缓存
     *
     * @param key
     *               键
     * @param time
     *               时间(秒)
     * @param values
     *               值 可以是多个
     *               成功个数
     */
    public long zSSetAndTime(String key, long time, Object... values) {
        try {
            Long count = redisTemplate.opsForSet().add(key, values);
            if (time > 0)
                expire(key, time);
            return count;
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    /**
     * 获取set缓存的长度
     *
     * @param key
     *            键
     *
     */
    public long zSGetSetSize(String key) {
        try {
            return redisTemplate.opsForSet().size(key);
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    /**
     * 移除值为value的
     *
     * @param key
     *               键
     * @param values
     *               值 可以是多个
     *               移除的个数
     */
    public long zSetRemove(String key, Object... values) {
        try {
            Long count = redisTemplate.opsForSet().remove(key, values);
            return count;
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }
    // ===============================list=================================

    /**
     * 获取list缓存的内容
     *
     * @取出来的元素 总数 end-start+1
     *
     * @param key
     *              键
     * @param start
     *              开始 0 是第一个元素
     * @param end
     *              结束 -1代表所有值
     *
     */
    public List<Object> lGet(String key, long start, long end) {
        try {
            return redisTemplate.opsForList().range(key, start, end);
        } catch (Exception e) {
            log.error(key, e);
            return null;
        }
    }

    /**
     * 获取list缓存的长度
     *
     * @param key
     *            键
     *
     */
    public long lGetListSize(String key) {
        try {
            return redisTemplate.opsForList().size(key);
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    /**
     * 通过索引 获取list中的值
     *
     * @param key
     *              键
     * @param index
     *              索引 index>=0时， 0 表头，1 第二个元素，依次类推；index<0时，-1，表尾，-2倒数第二个元素，依次类推
     *
     */
    public Object lGetIndex(String key, long index) {
        try {
            return redisTemplate.opsForList().index(key, index);
        } catch (Exception e) {
            log.error(key, e);
            return null;
        }
    }

    /**
     * 将list放入缓存
     *
     * @param key
     *              键
     * @param value
     *              值
     *
     */
    public boolean lSet(String key, Object value) {
        try {
            redisTemplate.opsForList().rightPush(key, value);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 将list放入缓存
     *
     * @param key
     *              键
     * @param value
     *              值
     * @param time
     *              时间(秒)
     *
     */
    public boolean lSet(String key, Object value, long time) {
        try {
            redisTemplate.opsForList().rightPush(key, value);
            if (time > 0)
                expire(key, time);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 将list放入缓存
     *
     * @param key
     *              键
     * @param value
     *              值
     *
     */
    public boolean lSet(String key, List<Object> value) {
        try {
            redisTemplate.opsForList().rightPushAll(key, value);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 将list放入缓存
     *
     * @param key
     *              键
     * @param value
     *              值
     * @param time
     *              时间(秒)
     *
     */
    public boolean lSet(String key, List<Object> value, long time) {
        try {
            redisTemplate.opsForList().rightPushAll(key, value);
            if (time > 0)
                expire(key, time);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 根据索引修改list中的某条数据
     *
     * @param key
     *              键
     * @param index
     *              索引
     * @param value
     *              值
     *
     */
    public boolean lUpdateIndex(String key, long index, Object value) {
        try {
            redisTemplate.opsForList().set(key, index, value);
            return true;
        } catch (Exception e) {
            log.error(key, e);
            return false;
        }
    }

    /**
     * 移除N个值为value
     *
     * @param key
     *              键
     * @param count
     *              移除多少个
     * @param value
     *              值
     *              移除的个数
     */
    public long lRemove(String key, long count, Object value) {
        try {
            Long remove = redisTemplate.opsForList().remove(key, count, value);
            return remove;
        } catch (Exception e) {
            log.error(key, e);
            return 0;
        }
    }

    public Object rightPop(String key) {
        try {
            return redisTemplate.opsForList().rightPop(key);
        } catch (Exception e) {
            log.error(key, e);
            return null;
        }
    }

    public Object rightPopWithTimeout(String key, long timeout, TimeUnit unit) {
        try {
            return redisTemplate.opsForList().rightPop(key, timeout, unit);
        } catch (Exception e) {
            log.error(key, e);
            return null;
        }
    }

}
