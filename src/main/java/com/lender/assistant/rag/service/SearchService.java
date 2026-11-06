package com.lender.assistant.rag.service;

import com.lender.assistant.common.constant.SearchMode;
import com.lender.assistant.rag.model.response.SearchItemResponse;
import java.util.List;

/**
 * 混合检索（向量通道 + 关键词通道）。抽接口的理由：这是 rag 领域对外唯一的检索契约，
 * 后续（阈值调优、重排、缓存）只替换实现，不动调用方。
 */
public interface SearchService {

    /**
     * 问答检索：追问时把「当前问题」与「上一轮问题」分段传入，而不是由调用方拼成一个字符串。
     *
     * 分段的原因：关键词通道的 token 配额必须按段分配，否则拼接串按顺序截断会让上一轮长问题
     * 把当前问题的词全部挤掉（"响应字段呢？"这类追问会与上一轮召回完全相同）。
     *
     * @param question         当前问题
     * @param previousQuestion 上一轮用户提问；首问传 null
     */
    List<SearchItemResponse> hybridSearch(String question, String previousQuestion, int topK);

    /** 单查询检索（管理台检索测试 / 评估集），无"上一轮问题"分段；模式见 {@link SearchMode} */
    List<SearchItemResponse> search(String query, int topK, SearchMode mode);
}
