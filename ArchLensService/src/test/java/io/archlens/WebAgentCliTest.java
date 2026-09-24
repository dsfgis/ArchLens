package io.archlens;

import io.archlens.cli.WebAgentCli;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 网页协议使用真实 PG 与合成文件；覆盖修订、报告回读和旧回答拒绝。 */
@EnabledIfEnvironmentVariable(named="ARCHLENS_STORAGE_IT",matches="true")
class WebAgentCliTest {
    PgInvestigationStore store;
    final Path root=Path.of("examples/scenarios/mysql-postgresql").toAbsolutePath();
    @BeforeEach void setup()throws Exception {store=new PgInvestigationStore(new StorageConfig(System.getenv()));store.initialize();}
    List<com.fasterxml.jackson.databind.JsonNode> call(WebAgentCli.Input input)throws Exception {
        var bytes=new ByteArrayOutputStream();
        WebAgentCli.execute(input,store,null,new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8));
        List<com.fasterxml.jackson.databind.JsonNode> rows=new ArrayList<>();
        for(String line:bytes.toString(java.nio.charset.StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank())rows.add(Json.MAPPER.readTree(line));
        return rows;
    }
    @Test void clarificationHistoryAndReportSurviveNewBridgeInstances()throws Exception {
        Request request=Json.MAPPER.readValue(root.resolve("agent-clarify.json").toFile(),Request.class);
        var events=call(new WebAgentCli.Input("start",root.toString(),request,null,null,null,null));
        assertEquals(List.of("started","sealed"),events.stream().map(n->n.path("event").asText()).toList());
        UUID firstId=UUID.fromString(events.getFirst().path("value").path("runId").asText());
        var saved=store.load(firstId);Report first=Json.MAPPER.readValue(saved.reportJson(),Report.class);
        assertEquals(Status.NEEDS_CLARIFICATION,first.status());
        var answers=new Answers("archlens.agent.v1",saved.reportHash(),Map.of(first.questions().getFirst().questionId(),"8.0.36"));
        var resume=new WebAgentCli.Input("resume",root.toString(),null,firstId,answers,null,null);
        var next=call(resume);UUID secondId=UUID.fromString(next.getFirst().path("value").path("runId").asText());
        store=new PgInvestigationStore(new StorageConfig(System.getenv()));
        var second=call(new WebAgentCli.Input("get",null,null,secondId,null,null,null)).getFirst().path("value");
        assertEquals("PARTIAL",second.path("report").path("status").asText());
        assertEquals(2,second.path("revision").asInt());assertTrue(second.path("latestRevision").asBoolean());
        assertFalse(second.path("report").path("investigation").path("findings").isEmpty());
        var history=call(new WebAgentCli.Input("list",null,null,null,null,saved.caseId(),0)).getFirst().path("value").path("runs");
        assertEquals(2,history.size());assertEquals(2,history.get(0).path("revision").asInt());assertFalse(history.get(1).path("latestRevision").asBoolean());
        assertEquals(saved.reportHash(),store.load(firstId).reportHash());
        ContractTest.assertCode("STALE_ANSWERS",()->call(resume));
        assertEquals(2,store.listAgentRuns(saved.caseId(),0).size());
    }
    @Test void invalidAnswerDoesNotAllocateRevisionAndCancelRetainsEvidence()throws Exception {
        Request request=Json.MAPPER.readValue(root.resolve("agent-clarify.json").toFile(),Request.class);
        var first=call(new WebAgentCli.Input("start",root.toString(),request,null,null,null,null));
        UUID id=UUID.fromString(first.getFirst().path("value").path("runId").asText());var saved=store.load(id);
        var bad=new Answers("archlens.agent.v1",saved.reportHash(),Map.of("wrong-question","8.0"));
        ContractTest.assertCode("INVALID_ANSWERS",()->call(new WebAgentCli.Input("resume",root.toString(),null,id,bad,null,null)));
        assertEquals(1,store.listAgentRuns(saved.caseId(),0).size());
        var ticket=store.beginAgent(null,request,null);store.checkpoint(ticket,List.of());
        assertTrue(call(new WebAgentCli.Input("cancel",null,null,ticket.runId(),null,null,null)).getFirst().path("value").path("cancelled").asBoolean());
        var cancelled=call(new WebAgentCli.Input("get",null,null,ticket.runId(),null,null,null)).getFirst().path("value");
        assertEquals("CANCELLED",cancelled.path("state").asText());assertTrue(cancelled.path("report").isNull());assertTrue(cancelled.path("checkpoint").isArray());
        ContractTest.assertCode("INVALID_PAGE",()->store.listAgentRuns(null,-1));
    }
}
