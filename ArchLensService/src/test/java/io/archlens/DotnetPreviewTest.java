package io.archlens;

import io.archlens.agent.AgentContracts;
import io.archlens.cli.WebAgentCli;
import io.archlens.contract.*;
import io.archlens.investigation.InvestigationRequest.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DotnetPreviewTest {
    @TempDir Path root;
    AgentContracts.Request request(List<String> files,String column) {
        return new AgentContracts.Request(AgentContracts.VERSION,"本地 .NET 平台现状预览",
                new AgentContracts.Target(column==null?Scenario.CURRENT_STATE:Scenario.COLUMN_CHANGE,new Profile(".NET",null),null),
                List.of(),List.of(),files,column,Budget.defaults(),new AgentContracts.Limits(1,1,30000));
    }
    WebAgentCli.Input input(AgentContracts.Request request){return new WebAgentCli.Input("preview-dotnet",root.toString(),request,null,null,null,null);}
    @Test void previewUsesActualFilesWithoutStoreOrModelAndProducesNoRunTicket() throws Exception {
        Files.writeString(root.resolve("app.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        var out=new ByteArrayOutputStream();
        WebAgentCli.execute(input(request(List.of("app.csproj"),null)),null,(a,b,c)->{fail("Preview must not invoke model");return null;},new PrintStream(out));
        var message=Json.MAPPER.readTree(out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("result",message.path("event").asText());var result=message.path("value");
        assertEquals("LOCAL_PREVIEW",result.path("mode").asText());assertFalse(result.path("persisted").asBoolean());assertFalse(result.has("runId"));
        assertEquals(1,result.path("report").path("dotnetInventory").path("projects").size());
        assertEquals(Json.hash(result.path("report")),result.path("reportHash").asText());
        try(var entries=Files.list(root)){assertEquals(1,entries.count());}
    }
    @Test void previewRejectsColumnAdapterAndMissingProjectManifest() throws Exception {
        var out=new PrintStream(new ByteArrayOutputStream());
        ContractTest.assertCode("COLUMN_WEB_UNSUPPORTED",()->WebAgentCli.execute(input(request(List.of("app.csproj"),"request.json")),null,null,out));
        ContractTest.assertCode("DOTNET_PROJECT_REQUIRED",()->WebAgentCli.execute(input(request(List.of("source.cs"),null)),null,null,out));
    }
    @Test void unavailableProjectRemainsPartialInsteadOfEmptySuccess() throws Exception {
        var out=new ByteArrayOutputStream();WebAgentCli.execute(input(request(List.of("missing.csproj"),null)),null,null,new PrintStream(out));
        var report=Json.MAPPER.readTree(out.toString(java.nio.charset.StandardCharsets.UTF_8)).path("value").path("report");
        assertEquals("PARTIAL",report.path("status").asText());assertFalse(report.has("dotnetInventory"));
        assertTrue(report.path("coverageGaps").toString().contains("SOURCE_UNAVAILABLE"));
    }
}
