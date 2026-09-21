package com.riskwarning.common.utils;

import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.reliability.redis.RedisClaimDeserializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUtilReliableQueueTest {

        private final RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        private final RedisConnection connection = mock(RedisConnection.class);
        private final RedisSerializer<Object> keySerializer = mock(RedisSerializer.class);
        private final RedisSerializer<Object> valueSerializer = mock(RedisSerializer.class);
        private final RedisUtil redis = new RedisUtil();

        private final byte[] readyKey = new byte[] { 1 };
        private final byte[] pendingKey = new byte[] { 2 };
        private final byte[] deadKey = new byte[] { 3 };

        @BeforeEach
        void setup() {
                ReflectionTestUtils.setField(redis, "redisTemplate", template);
                when(template.getKeySerializer()).thenReturn((RedisSerializer) keySerializer);
                when(template.getValueSerializer()).thenReturn((RedisSerializer) valueSerializer);
                when(keySerializer.serialize("ready")).thenReturn(readyKey);
                when(keySerializer.serialize("pending")).thenReturn(pendingKey);
                when(keySerializer.serialize("dead")).thenReturn(deadKey);
                when(template.execute(any(RedisCallback.class))).thenAnswer(
                                invocation -> ((RedisCallback<?>) invocation.getArgument(0)).doInRedis(connection));
        }

        @Test
        void recoversPendingBeforeClaimingReadyAndAcknowledgesOriginalBytes() {
                byte[] raw = new byte[] { 9, 8, 7 };
                when(connection.eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.VALUE),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(readyKey), aryEq(pendingKey)))
                                .thenReturn(raw);
                when(valueSerializer.deserialize(raw)).thenReturn("task");
                when(connection.lRem(pendingKey, 1, raw)).thenReturn(1L);

                ClaimedRedisItem<String> claimed = redis.claimListItem("ready", "pending", String.class);
                redis.acknowledgeListItem(claimed);

                assertEquals("task", claimed.getValue());
                assertArrayEquals(raw, claimed.getRawValue());
                verify(connection).eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.VALUE),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(readyKey), aryEq(pendingKey));
                verify(connection).lRem(aryEq(pendingKey), org.mockito.ArgumentMatchers.eq(1L), aryEq(raw));
        }

        @Test
        void claimsReadyAtomicallyWhenPendingIsEmpty() {
                byte[] raw = new byte[] { 6 };
                when(connection.eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.VALUE),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(readyKey), aryEq(pendingKey)))
                                .thenReturn(raw);
                when(valueSerializer.deserialize(raw)).thenReturn("task");

                ClaimedRedisItem<String> claimed = redis.claimListItem("ready", "pending", String.class);

                assertEquals("task", claimed.getValue());
                verify(connection).eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.VALUE),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(readyKey), aryEq(pendingKey));
        }

        @Test
        void retainsRawBytesWhenDeserializationFails() {
                byte[] raw = new byte[] { 5, 4 };
                when(connection.eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.VALUE),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(readyKey), aryEq(pendingKey)))
                                .thenReturn(raw);
                when(valueSerializer.deserialize(raw)).thenThrow(new IllegalStateException("poison"));

                RedisClaimDeserializationException failure = assertThrows(
                                RedisClaimDeserializationException.class,
                                () -> redis.claimListItem("ready", "pending", String.class));

                assertArrayEquals(raw, failure.getRawValue());
                assertEquals("pending", failure.getPendingKey());
        }

        @Test
        void movesPoisonTaskToDeadAtomically() {
                byte[] raw = new byte[] { 5 };
                byte[] serializedDead = new byte[] { 7 };
                Object deadLetter = new Object();
                when(valueSerializer.serialize(deadLetter)).thenReturn(serializedDead);
                when(connection.eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.INTEGER),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(pendingKey), aryEq(deadKey),
                                aryEq(raw), aryEq(serializedDead))).thenReturn(1L);

                redis.deadLetterListItem("pending", "dead", raw, deadLetter);

                verify(connection).eval(any(byte[].class), org.mockito.ArgumentMatchers.eq(ReturnType.INTEGER),
                                org.mockito.ArgumentMatchers.eq(2), aryEq(pendingKey), aryEq(deadKey),
                                aryEq(raw), aryEq(serializedDead));
        }
}
