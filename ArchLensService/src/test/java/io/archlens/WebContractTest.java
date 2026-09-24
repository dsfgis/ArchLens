package io.archlens;

import io.archlens.cli.WebAgentCli;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 不依赖外部存储的协议负例；所有拒绝都发生在调用存储之前。 */
class WebContractTest {
    @Test void strictEnvelopeRejectsExtraFieldsAndCredentialInjection() {
        assertThrows(Exception.class,()->Json.MAPPER.readValue("{\"action\":\"list\",\"password\":\"synthetic\"}",WebAgentCli.Input.class));
        assertThrows(Exception.class,()->Json.MAPPER.readValue("{\"action\":\"list\",\"action\":\"cancel\"}",WebAgentCli.Input.class));
        assertThrows(Exception.class,()->Json.MAPPER.readValue("{\"action\":\"get\",\"runId\":\"not-a-uuid\"}",WebAgentCli.Input.class));
    }
    @Test void actionsDoNotAcceptUnrelatedFieldsOrArbitraryCommands() {
        ContractTest.assertCode("INVALID_REQUEST",()->WebAgentCli.execute(new WebAgentCli.Input("exec",null,null,null,null,null,null),null,null,System.out));
        ContractTest.assertCode("INVALID_REQUEST",()->WebAgentCli.execute(new WebAgentCli.Input("list","private-root",null,null,null,null,null),null,null,System.out));
        ContractTest.assertCode("INVALID_REQUEST",()->WebAgentCli.execute(new WebAgentCli.Input("cancel",null,null,null,null,null,null),null,null,System.out));
        ContractTest.assertCode("INVALID_REQUEST",()->WebAgentCli.execute(new WebAgentCli.Input("start","relative/root",null,null,null,null,null),null,null,System.out));
    }
    @Test void requestPathsRemainInsideExplicitRootAndUnicodeSurvivesProtocol()throws Exception {
        String request=Files.readString(Path.of("examples/scenarios/mysql-postgresql/agent-clarify.json"));
        var decoded=Json.MAPPER.readValue(request,Request.class);
        var input=new WebAgentCli.Input("start",Path.of("examples/scenarios/mysql-postgresql").toAbsolutePath().toString(),decoded,null,null,null,null);
        assertEquals(input,Json.MAPPER.readValue(Json.canonical(input),WebAgentCli.Input.class));
        assertTrue(input.request().objective().contains("调查"));
        assertThrows(Exception.class,()->Json.MAPPER.readValue(request.replace("schema.sql","../private.sql"),Request.class));
    }
}
