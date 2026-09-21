package com.riskwarning.common.reliability;

import com.riskwarning.common.message.Message;

/**
 * 业务事务内的消息投递入口。
 * 可靠模式：消息与业务更新同事务写入 PostgreSQL Outbox，由 Worker 发送并重试。
 * 兼容模式：事务提交后投递，行为与直发一致。
 */
public interface KafkaOutbox {

    /** 必须在业务事务内调用；消息与业务更新同事务提交。 */
    void enqueue(Message message);

    /** true 表示消息已持久化到 PostgreSQL Outbox。 */
    boolean isDurable();
}
