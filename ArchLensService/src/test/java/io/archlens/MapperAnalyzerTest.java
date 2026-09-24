package io.archlens;

import io.archlens.analysis.*;
import io.archlens.cli.ArchLensCli;
import io.archlens.contract.*;
import io.archlens.parser.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.archlens.contract.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class MapperAnalyzerTest {
    @TempDir Path temp;
    Path example=Path.of("examples/column-rename");
    OfflineRequest input() throws IOException { return Json.MAPPER.readValue(example.resolve("request.json").toFile(),OfflineRequest.class); }
    void setup(String sql) throws IOException {
        Files.copy(example.resolve("DeviceMapper.java"),temp.resolve("DeviceMapper.java"));
        Files.writeString(temp.resolve("DeviceMapper.xml"),"<mapper namespace=\"demo.DeviceMapper\"><select id=\"listDevices\">"+sql+"</select></mapper>");
    }
    GraphDocument analyze() throws Exception { return new MapperAnalyzer().analyze(temp,input()); }
    @Test void staticMapperBindsRealJavaDeclarationAndOriginalBytes() throws Exception {
        var document=new MapperAnalyzer().analyze(example,input()); var graph=new FactGraph(document);
        assertEquals(2,document.edges().size(),()->document.diagnostics().toString());
        assertEquals(3,document.nodes().size());
        for(var evidence:document.evidence()) {
            byte[] bytes=Files.readAllBytes(example.resolve(evidence.location().path()));
            assertEquals(Json.sha256(bytes),evidence.sourceHash());
            assertEquals(bytes.length,evidence.location().byteLength()); assertEquals(0,evidence.location().byteOffset());
            assertEquals(new String(bytes,StandardCharsets.UTF_8),evidence.snippet());
        }
        assertEquals(Json.hash(document),Json.hash(new MapperAnalyzer().analyze(example,input())));
        assertTrue(graph.document().diagnostics().stream().anyMatch(d->d.code().equals("OFFLINE_CATALOG_UNVERIFIED")));
    }
    @Test void sqlAliasKeepsPhysicalColumnDependency() throws Exception {
        setup("SELECT event_id AS global_id FROM public.device"); var doc=analyze();
        assertEquals(1,doc.edges().size(),()->doc.diagnostics().toString());
        var graph=new FactGraph(doc); assertEquals("event_id",graph.node(doc.edges().getFirst().toId()).name());
    }
    @Test void dynamicIdentifierAndNestedBranchNeverInventEdges() throws Exception {
        setup("SELECT event_id FROM ${tableName}"); var dynamic=analyze();
        assertTrue(dynamic.edges().isEmpty()); assertTrue(dynamic.diagnostics().stream().anyMatch(d->d.code().equals("DYNAMIC_IDENTIFIER")));
        Files.writeString(temp.resolve("DeviceMapper.xml"),"<mapper namespace=\"demo.DeviceMapper\"><select id=\"listDevices\">SELECT event_id FROM device <if test=\"x != null\">WHERE name = #{x}</if></select></mapper>");
        assertTrue(analyze().edges().isEmpty());
    }
    @Test void joinAndSubqueryDoNotGuessBindings() throws Exception {
        setup("SELECT d.event_id FROM device d JOIN other o ON o.id=d.event_id"); assertTrue(analyze().edges().isEmpty());
        Files.writeString(temp.resolve("DeviceMapper.xml"),"<mapper namespace=\"demo.DeviceMapper\"><select id=\"listDevices\">SELECT event_id FROM (SELECT event_id FROM device) d</select></mapper>");
        assertTrue(analyze().edges().isEmpty());
    }
    @Test void unresolvedExpressionAndColumnKeepDiagnostics() throws Exception {
        setup("SELECT upper(name), missing FROM device"); var doc=analyze();
        assertTrue(doc.edges().isEmpty());
        assertTrue(doc.diagnostics().stream().anyMatch(d->d.code().equals("UNSUPPORTED_EXPRESSION")));
        assertTrue(doc.diagnostics().stream().anyMatch(d->d.code().equals("UNRESOLVED_COLUMN")));
    }
    @Test void postgresUnquotedCaseAndAliasAreResolvedButQuotedCaseIsPreserved() throws Exception {
        setup("SELECT d.EVENT_ID FROM PUBLIC.DEVICE d WHERE d.name = '中文'"); assertEquals(2,analyze().edges().size());
        Files.writeString(temp.resolve("DeviceMapper.xml"),"<mapper namespace=\"demo.DeviceMapper\"><select id=\"listDevices\">SELECT &quot;EVENT_ID&quot; FROM device</select></mapper>");
        assertTrue(analyze().edges().isEmpty());
    }
    @Test void namespaceMismatchAndDuplicateStatementsDoNotBind() throws Exception {
        setup("SELECT event_id FROM device");
        String xml=Files.readString(temp.resolve("DeviceMapper.xml"));
        Files.writeString(temp.resolve("DeviceMapper.xml"),xml.replace("demo.DeviceMapper","other.DeviceMapper"));
        assertTrue(analyze().edges().isEmpty());
        Files.writeString(temp.resolve("DeviceMapper.xml"),xml.replace("</mapper>","<select id=\"listDevices\">SELECT name FROM device</select></mapper>"));
        assertTrue(analyze().edges().isEmpty());
    }
    @Test void schemaQualifierAndAnnotationConflictsCannotBeGuessed() throws Exception {
        setup("SELECT other.device.event_id FROM public.device"); assertTrue(analyze().edges().isEmpty());
        Files.writeString(temp.resolve("DeviceMapper.xml"),"<mapper namespace=\"demo.DeviceMapper\"><select id=\"listDevices\">SELECT event_id FROM device</select></mapper>");
        Files.writeString(temp.resolve("DeviceMapper.java"),"package demo; public interface DeviceMapper { @Select(\"SELECT name FROM device\") String listDevices(); }");
        assertTrue(analyze().edges().isEmpty());
    }
    @Test void xmlEntitiesAreRejectedWithoutReadingExternalFiles() throws Exception {
        setup("SELECT event_id FROM device");
        Files.writeString(temp.resolve("DeviceMapper.xml"),"<!DOCTYPE mapper [<!ENTITY secret SYSTEM 'file:///C:/Windows/win.ini'>]><mapper namespace=\"demo.DeviceMapper\"><select id=\"listDevices\">&secret;</select></mapper>");
        var doc=analyze(); assertTrue(doc.edges().isEmpty()); assertTrue(doc.diagnostics().stream().anyMatch(d->d.code().equals("XML_REJECTED")));
    }
    @Test void sourcePathCannotEscapeAndInvalidUtf8IsRejected() throws Exception {
        Path child=Files.createDirectory(temp.resolve("child")); Files.writeString(temp.resolve("outside"),"secret");
        ContractTest.assertCode("PATH_OUTSIDE_ROOT",()->SourceText.read(child,"../outside"));
        Files.write(child.resolve("bad"),new byte[]{(byte)0xC3,0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class,()->SourceText.read(child,"bad"));
    }
    @Test void lineColumnAndByteOffsetsUseOriginalUnicodeAndCrLf() throws Exception {
        String text="中文😀\r\nx"; Files.writeString(temp.resolve("unicode"),text);
        var source=SourceText.read(temp,"unicode");
        assertEquals(2,source.location().endLine()); assertEquals(2,source.location().endColumn());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length,source.location().byteLength());
    }
    @Test void packagedEntryFlowProducesReportAndNeverOverwritesSourceOrPreviousReport() throws Exception {
        ByteArrayOutputStream log=new ByteArrayOutputStream(); PrintStream sink=new PrintStream(log, true, StandardCharsets.UTF_8);
        Path output=temp.resolve("report.json");
        String[] args={"analyze",example.resolve("request.json").toString(),output.toString()};
        assertEquals(0,ArchLensCli.run(args,sink,sink),log.toString());
        var report=Json.MAPPER.readTree(output.toFile());
        assertEquals("PARTIAL",report.at("/analysis/status").asText());
        assertEquals(2,report.at("/graph/edges").size());
        assertTrue(report.at("/analysis/impacts").findValuesAsText("changeRequired").contains("YES"));
        byte[] before=Files.readAllBytes(output); assertEquals(2,ArchLensCli.run(args,sink,sink)); assertArrayEquals(before,Files.readAllBytes(output));
        args[2]=example.resolve("DeviceMapper.java").toString();
        byte[] javaBefore=Files.readAllBytes(Path.of(args[2])); assertEquals(2,ArchLensCli.run(args,sink,sink)); assertArrayEquals(javaBefore,Files.readAllBytes(Path.of(args[2])));
    }
}
