package com.riskwarning.common.provider;

/**
 * 大模型对话调用的稳定边界。
 *
 * 业务代码只依赖本接口，不得依赖静态密钥、具体供应商的响应结构或其 SDK 类型。
 * 事实抽取、合规分析与后续可能的 Embedding 调用都应经由此类边界接入，
 * 使供应商更换不扩散到调用方。
 */
public interface AiChatProvider {

    /**
     * 以单轮用户消息发起调用，返回模型输出的正文文本。
     *
     * 实现方负责重试、超时与错误分类；返回值不含供应商包装结构。
     *
     * @param prompt 完整提示词，不得为空
     * @return 模型输出正文，非空
     * @throws LlmProviderException 调用失败或响应结构不可解析；失败不会被静默吞掉
     */
    String chat(String prompt);

    /**
     * 当前生效的模型标识，用于写入结果的模型版本字段。
     */
    String modelId();
}
