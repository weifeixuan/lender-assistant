package com.lender.assistant.rag.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 工具注册表：按计划里的 tool 名字直接取实现，不建 Factory。 */
@Component
public class PlanToolRegistry {

    private final Map<String, AgentTool> tools;

    public PlanToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(byName);
    }

    public Optional<AgentTool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> names() {
        return tools.keySet();
    }
}
