package com.riskwarning.common.utils;

import com.riskwarning.common.message.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;

import java.util.HashSet;
import java.util.Stack;
import java.util.concurrent.ExecutionException;

@Component
public class KafkaUtils {

    @Autowired
    private KafkaTemplate<String, Message> kafkaTemplate;

    public  void sendMessage(Message message) {
        kafkaTemplate.send(message.getTopic().getTopicName(), message);
    }

    /** 等待 Broker 确认，用于数据库提交后的首条任务消息。 */
    public SendResult<String, Message> sendMessageAndWait(Message message)
            throws InterruptedException, ExecutionException {
        ListenableFuture<SendResult<String, Message>> future =
                kafkaTemplate.send(message.getTopic().getTopicName(), message);
        return future.get();
    }

    public static void main(String[] args) {
        HashSet<String> hashSet = new HashSet<>();
        hashSet.add("1");
        hashSet.add("2");
        Stack[] stacks = new Stack[2];

    }
}
