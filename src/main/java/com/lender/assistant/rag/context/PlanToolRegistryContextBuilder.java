package com.lender.assistant.rag.context;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 工具注册表：按计划里的 tool 名字直接取实现，不建 Factory。 */
@Component
public class PlanToolRegistryContextBuilder {

    private final Map<String, AgentToolContextBuilder> tools;

    public PlanToolRegistryContextBuilder(List<AgentToolContextBuilder> tools) {
        Map<String, AgentToolContextBuilder> byName = new LinkedHashMap<>();
        for (AgentToolContextBuilder tool : tools) {
            byName.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(byName);
    }

    public Optional<AgentToolContextBuilder> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> names() {
        return tools.keySet();
    }
}
