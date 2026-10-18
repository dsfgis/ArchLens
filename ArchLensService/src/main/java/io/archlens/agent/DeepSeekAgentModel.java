package io.archlens.agent;

import io.archlens.llm.ModelConfig;
import java.net.http.HttpRequest;
import java.util.*;

/** Compatibility adapter for legacy DEEPSEEK_* configuration. */
public final class DeepSeekAgentModel implements AgentModel {
    public record Reply(int status,String body) {}
    @FunctionalInterface public interface Transport {Reply send(HttpRequest request) throws Exception;}
    private final ConfiguredAgentModel delegate;
    public DeepSeekAgentModel(String key,String model){delegate=new ConfiguredAgentModel(ModelConfig.legacyDeepSeek(key,model));}
    public DeepSeekAgentModel(String key,String model,Transport transport){
        delegate=new ConfiguredAgentModel(ModelConfig.legacyDeepSeek(key,model),request->{var r=transport.send(request);return new ConfiguredAgentModel.Reply(r.status(),r.body());});
    }
    public Call next(List<Map<String,Object>> messages,List<Map<String,Object>> tools,long timeoutMillis)throws Exception{return delegate.next(messages,tools,timeoutMillis);}
}
