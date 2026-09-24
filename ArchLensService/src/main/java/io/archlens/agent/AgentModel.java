package io.archlens.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 可替换的模型传输边界；生产实现使用 DeepSeek 原生工具调用，测试可用固定响应验证权限。 */
@FunctionalInterface
public interface AgentModel {
    record Call(String id,String name,JsonNode arguments) {}
    Call next(List<Map<String,Object>> messages,List<Map<String,Object>> tools,long timeoutMillis) throws Exception;
}
