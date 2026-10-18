package io.archlens;

import io.archlens.investigation.dotnet.DotnetSemantics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DotnetSemanticsTest {
    @TempDir Path root;

    @Test void explicitSourceScopeAndRoslynBinding() throws Exception {
        Path source=root.resolve("Program.cs");
        Files.writeString(source,"class A { public void Run() { var marker = \"😀\"; Helper(); Missing.Call(); } void Helper() {} }\n");
        Files.writeString(root.resolve("Other.cs"),"class SecretType {}\n");
        var result=DotnetSemantics.inspect(root,List.of("Program.cs"),()->false,10000);
        Assumptions.assumeTrue(!result.status().equals("UNAVAILABLE"),"Build the local Roslyn host for integration validation");
        assertEquals("PARTIAL",result.status());
        assertTrue(result.facts().stream().anyMatch(f->f.kind().equals("INVOCATION")&&f.binding().equals("SOURCE_BOUND")&&f.target().contains("Helper")));
        assertTrue(result.facts().stream().anyMatch(f->f.kind().equals("INVOCATION")&&f.binding().equals("UNRESOLVED")));
        assertTrue(result.facts().stream().allMatch(f->f.path().equals("Program.cs")&&f.line()>0&&f.column()>0&&f.sha256().matches("[a-f0-9]{64}")));
        var call=result.facts().stream().filter(f->f.kind().equals("INVOCATION")&&f.binding().equals("SOURCE_BOUND")).findFirst().orElseThrow();
        byte[] bytes=Files.readAllBytes(source);
        assertEquals("Helper()",new String(Arrays.copyOfRange(bytes,(int)call.startByte(),(int)call.endByte()),java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(result.gaps().contains("COMPILATION_ERRORS_OR_MISSING_REFERENCES"));
        assertFalse(result.projection().toString().contains("Helper"));
        assertFalse(result.projection().toString().contains("Program.cs"));
    }
    @Test void noAuthorizedCsharpNeverReadsUnlistedSource() throws Exception {
        Files.writeString(root.resolve("Private.cs"),"class Private {}\n");
        var result=DotnetSemantics.inspect(root,List.of("README.md"),()->false,1000);
        assertEquals("UNAVAILABLE",result.status());
        assertEquals(List.of("NO_AUTHORIZED_CSHARP_SOURCES"),result.gaps());
    }
    @Test void sameTypeNameInSeparateProjectsDoesNotCreateFalseCrossProjectBinding() throws Exception {
        Files.createDirectories(root.resolve("A"));Files.createDirectories(root.resolve("B"));
        Files.writeString(root.resolve("A/A.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"/>");
        Files.writeString(root.resolve("B/B.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"/>");
        Files.writeString(root.resolve("A/Item.cs"),"class Item { void Go() { Own(); } void Own() {} }\n");
        Files.writeString(root.resolve("B/Item.cs"),"class Item { void Go() { Own(); } void Own() {} }\n");
        var result=DotnetSemantics.inspect(root,List.of("A/A.csproj","B/B.csproj","A/Item.cs","B/Item.cs"),()->false,10000);
        Assumptions.assumeTrue(!result.status().equals("UNAVAILABLE"),"Build the local Roslyn host for integration validation");
        assertEquals(2,result.facts().stream().filter(f->f.kind().equals("TYPE_DECLARATION")).count());
        assertEquals(2,result.facts().stream().filter(f->f.kind().equals("INVOCATION")&&f.binding().equals("SOURCE_BOUND")).count());
        assertFalse(result.gaps().contains("COMPILATION_ERRORS_OR_MISSING_REFERENCES"));
        assertEquals(Set.of("A/A.csproj","B/B.csproj"),result.facts().stream().map(DotnetSemantics.Fact::project).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void declaredProjectReferenceBindsAcrossProjectsButUnlistedProjectDoesNot() throws Exception {
        Files.createDirectories(root.resolve("A"));Files.createDirectories(root.resolve("B"));Files.createDirectories(root.resolve("C"));
        Files.writeString(root.resolve("A/A.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><ProjectReference Include=\"..\\B\\B.csproj\" /></ItemGroup></Project>");
        Files.writeString(root.resolve("B/B.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\" />");
        Files.writeString(root.resolve("C/C.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\" />");
        Files.writeString(root.resolve("A/Caller.cs"),"class Caller { void Go() { Shared.Ping(); } }\n");
        Files.writeString(root.resolve("B/Shared.cs"),"public class Shared { public static void Ping() {} }\n");
        Files.writeString(root.resolve("C/Shared.cs"),"public class Shared { public static void Ping() {} }\n");
        var result=DotnetSemantics.inspect(root,List.of("A/A.csproj","B/B.csproj","C/C.csproj","A/Caller.cs","B/Shared.cs","C/Shared.cs"),()->false,10000);
        Assumptions.assumeTrue(!result.status().equals("UNAVAILABLE"),"Build the local Roslyn host for integration validation");
        assertFalse(result.gaps().contains("PROJECT_REFERENCES_NOT_RESOLVED"));
        assertFalse(result.gaps().contains("CONDITIONAL_SYMBOLS_NOT_EVALUATED"));
        assertTrue(result.facts().stream().anyMatch(f->f.path().equals("A/Caller.cs")&&f.kind().equals("INVOCATION")
                &&f.binding().equals("SOURCE_BOUND")&&f.target().contains("Shared.Ping")));
        Files.writeString(root.resolve("A/A.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\" />");
        var isolated=DotnetSemantics.inspect(root,List.of("A/A.csproj","B/B.csproj","C/C.csproj","A/Caller.cs","B/Shared.cs","C/Shared.cs"),()->false,10000);
        assertTrue(isolated.facts().stream().anyMatch(f->f.path().equals("A/Caller.cs")&&f.kind().equals("INVOCATION")&&f.binding().equals("UNRESOLVED")));
    }
    @Test void sdkImplicitUsingsBindCommonFrameworkCallsWithoutEmittingSyntheticFacts() throws Exception {
        Files.writeString(root.resolve("Demo.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>");
        Files.writeString(root.resolve("Demo.cs"),"class Demo { int[] Empty() { return Array.Empty<int>(); } }\n");
        var result=DotnetSemantics.inspect(root,List.of("Demo.csproj","Demo.cs"),()->false,10000);
        Assumptions.assumeTrue(!result.status().equals("UNAVAILABLE"),"Build the local Roslyn host for integration validation");
        assertTrue(result.facts().stream().anyMatch(f->f.kind().equals("INVOCATION")&&f.binding().equals("HOST_REFERENCE_BOUND")&&f.target().contains("Array.Empty")));
        assertTrue(result.facts().stream().allMatch(f->f.path().equals("Demo.cs")));
    }
}
