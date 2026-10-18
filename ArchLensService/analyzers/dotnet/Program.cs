using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Xml;
using System.Xml.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

// Static Roslyn compilation: supplied texts only. Never load projects, run MSBuild, analyzers, or generators.
const int MaxFacts = 20000;
try
{
    string input = Console.In.ReadToEnd();
    var request = JsonSerializer.Deserialize<Request>(input, new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
                  ?? throw new InvalidDataException("Missing request");
    if (request.Sources is null || request.Sources.Count is < 1 or > 1000)
        throw new InvalidDataException("Invalid source count");
    var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
    var projectsByPath = new Dictionary<string, string?>(StringComparer.Ordinal);
    long bytes = 0;
    foreach (var source in request.Sources)
    {
        if (string.IsNullOrWhiteSpace(source.Path) || source.Path.Length > 1000 || source.Text is null ||
            !source.Path.EndsWith(".cs", StringComparison.OrdinalIgnoreCase) ||
            !hashes.TryAdd(source.Path, source.Sha256))
            throw new InvalidDataException("Invalid source identity");
        byte[] raw = Encoding.UTF8.GetBytes(source.Text);
        bytes += raw.Length;
        if (raw.Length > 5_000_000 || bytes > 50_000_000 ||
            !Convert.ToHexStringLower(SHA256.HashData(raw)).Equals(source.Sha256, StringComparison.Ordinal))
            throw new InvalidDataException("Source hash or size mismatch");
        projectsByPath[source.Path] = source.Project;
    }
    var gaps = new List<string> { "PROJECT_COMPILE_ITEMS_NOT_EVALUATED", "HOST_REFERENCES_NOT_TARGET_FRAMEWORK",
                                  "GENERATED_SOURCES_NOT_INCLUDED", "RUNTIME_BEHAVIOR_NOT_VERIFIED" };
    var projectDocuments = request.Projects ?? new List<ProjectDocument>();
    if (projectDocuments.Count > 1000) throw new InvalidDataException("Project count limit");
    var projectConfigs = new Dictionary<string, ProjectConfig>(StringComparer.Ordinal);
    foreach (var document in projectDocuments)
    {
        bytes += document.Text is null ? 0 : Encoding.UTF8.GetByteCount(document.Text);
        if (string.IsNullOrWhiteSpace(document.Path) || !document.Path.EndsWith(".csproj", StringComparison.OrdinalIgnoreCase) ||
            document.Text is null || bytes > 50_000_000 || document.Text.Length > 2_000_000 ||
            !projectConfigs.TryAdd(document.Path, ProjectConfig.Read(document, gaps)))
            throw new InvalidDataException("Invalid project identity");
    }
    if (projectsByPath.Values.Any(p => p is not null && !projectConfigs.ContainsKey(p)))
        gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED");
    if (projectConfigs.Values.Any(p => p.ParsedLanguage is null)) gaps.Add("PROJECT_LANGUAGE_VERSION_NOT_EVALUATED");
    if (projectConfigs.Count == 0) gaps.Add("PROJECT_LANGUAGE_VERSION_NOT_EVALUATED");
    if (projectConfigs.Values.Any(p => p.HasConditions) || request.Sources.Any(s =>
        System.Text.RegularExpressions.Regex.IsMatch(s.Text, @"(?m)^\s*#(?:if|elif)\b")))
        gaps.Add("CONDITIONAL_SYMBOLS_NOT_EVALUATED");
    foreach (var project in projectConfigs.Values)
        foreach (var reference in project.References)
            if (!projectConfigs.ContainsKey(reference)) gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED");
    if (request.Sources.Any(s => s.Project is null)) gaps.Add("CSHARP_PROJECT_IDENTITY_MISSING");
    // Runtime reference assemblies are used only to bind common BCL symbols. This is not the analyzed project's target framework.
    var refs = ((string?)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES") ?? "")
        .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries)
        .Where(x => x.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
        .Select(x => MetadataReference.CreateFromFile(x)).ToList();
    var facts = new List<Fact>();
    var groups = request.Sources.GroupBy(s => s.Project ?? s.Path, StringComparer.Ordinal).ToList();
    var treesByGroup = groups.ToDictionary(g => g.Key,
        g => g.Select(s => CSharpSyntaxTree.ParseText(s.Text,
            new CSharpParseOptions(projectConfigs.GetValueOrDefault(g.Key)?.ParsedLanguage ?? LanguageVersion.Preview), s.Path)).ToList(),
        StringComparer.Ordinal);
    var compilations = new Dictionary<string, CSharpCompilation>(StringComparer.Ordinal);
    var building = new HashSet<string>(StringComparer.Ordinal);
    CSharpCompilation BuildCompilation(string key)
    {
        if (compilations.TryGetValue(key, out var existing)) return existing;
        if (!building.Add(key)) throw new InvalidDataException("Project reference cycle");
        var references = new List<MetadataReference>(refs);
        if (projectConfigs.TryGetValue(key, out var config))
            foreach (var dependency in config.References)
                if (treesByGroup.ContainsKey(dependency))
                {
                    if (building.Contains(dependency)) { gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED"); continue; }
                    references.Add(BuildCompilation(dependency).ToMetadataReference());
                }
                else gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED");
        var configForGroup = projectConfigs.GetValueOrDefault(key);
        var compilationTrees = new List<SyntaxTree>(treesByGroup[key]);
        var implicitUsings = configForGroup?.SyntheticUsings();
        if (implicitUsings is not null)
            compilationTrees.Add(CSharpSyntaxTree.ParseText(implicitUsings,
                new CSharpParseOptions(configForGroup!.ParsedLanguage ?? LanguageVersion.Preview), "<archlens-sdk-usings>"));
        var assemblyName = configForGroup?.AssemblyName ?? Path.GetFileNameWithoutExtension(key);
        var compilation = CSharpCompilation.Create(assemblyName, compilationTrees, references,
            new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary,
                concurrentBuild: false, metadataImportOptions: MetadataImportOptions.Public));
        building.Remove(key);
        compilations[key] = compilation;
        return compilation;
    }
    foreach (var group in groups)
    {
      var trees = treesByGroup[group.Key];
      var compilation = BuildCompilation(group.Key);
      foreach (var tree in trees)
      {
        var model = compilation.GetSemanticModel(tree);
        var root = tree.GetRoot();
        var nodes = root.DescendantNodes().Where(node => node is BaseTypeDeclarationSyntax or MethodDeclarationSyntax
            or ConstructorDeclarationSyntax or PropertyDeclarationSyntax or InvocationExpressionSyntax or ObjectCreationExpressionSyntax)
            .Take(MaxFacts - facts.Count).ToList();
        var sourceText = tree.GetText();
        var stops = nodes.SelectMany(n => new[] { n.SpanStart, n.Span.End }).Distinct().Order().ToList();
        var byteOffsets = new Dictionary<int, int>();
        int previous = 0, byteOffset = 0;
        foreach (int stop in stops)
        {
            byteOffset += Encoding.UTF8.GetByteCount(sourceText.ToString(new Microsoft.CodeAnalysis.Text.TextSpan(previous, stop - previous)));
            byteOffsets[stop] = byteOffset;
            previous = stop;
        }
        foreach (var node in nodes)
        {
            string? kind = null;
            ISymbol? symbol = null;
            ISymbol? target = null;
            if (node is BaseTypeDeclarationSyntax type) { kind = "TYPE_DECLARATION"; symbol = model.GetDeclaredSymbol(type); }
            else if (node is MethodDeclarationSyntax method) { kind = "METHOD_DECLARATION"; symbol = model.GetDeclaredSymbol(method); }
            else if (node is ConstructorDeclarationSyntax constructor) { kind = "CONSTRUCTOR_DECLARATION"; symbol = model.GetDeclaredSymbol(constructor); }
            else if (node is PropertyDeclarationSyntax property) { kind = "PROPERTY_DECLARATION"; symbol = model.GetDeclaredSymbol(property); }
            else if (node is InvocationExpressionSyntax invocation)
            {
                kind = "INVOCATION";
                symbol = model.GetEnclosingSymbol(node.SpanStart);
                var info = model.GetSymbolInfo(invocation);
                target = info.Symbol;
            }
            else if (node is ObjectCreationExpressionSyntax creation)
            {
                kind = "OBJECT_CREATION";
                symbol = model.GetEnclosingSymbol(node.SpanStart);
                target = model.GetSymbolInfo(creation).Symbol;
            }
            if (kind is null) continue;
            var lineSpan = tree.GetLineSpan(node.Span);
            var position = lineSpan.StartLinePosition;
            var endPosition = lineSpan.EndLinePosition;
            string origin = symbol?.ToDisplayString(SymbolDisplayFormat.CSharpErrorMessageFormat) ?? "UNRESOLVED";
            string? destination = target?.ToDisplayString(SymbolDisplayFormat.CSharpErrorMessageFormat);
            string binding = kind.EndsWith("DECLARATION", StringComparison.Ordinal) ? "DECLARED" :
                target is null ? "UNRESOLVED" :
                target.Locations.Any(x => x.IsInSource) ? "SOURCE_BOUND" : "HOST_REFERENCE_BOUND";
            string path = tree.FilePath;
            string idData = $"{group.Key}:{hashes[path]}:{kind}:{node.SpanStart}:{node.Span.End}:{origin}:{destination}";
            string id = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(idData)));
            int lineStart = sourceText.Lines[position.Line].Start;
            int endLineStart = sourceText.Lines[endPosition.Line].Start;
            int codePointColumn = sourceText.ToString(new Microsoft.CodeAnalysis.Text.TextSpan(lineStart, node.SpanStart - lineStart)).EnumerateRunes().Count() + 1;
            int endColumn = sourceText.ToString(new Microsoft.CodeAnalysis.Text.TextSpan(endLineStart, node.Span.End - endLineStart)).EnumerateRunes().Count() + 1;
            facts.Add(new Fact(id, kind, path, projectsByPath[path], hashes[path], position.Line + 1, codePointColumn,
                               endPosition.Line + 1, endColumn, byteOffsets[node.SpanStart], byteOffsets[node.Span.End],
                               origin, destination, binding));
        }
        if (facts.Count >= MaxFacts) { gaps.Add("FACT_LIMIT_REACHED"); break; }
      }
      if (compilation.GetDiagnostics().Any(d => d.Severity == DiagnosticSeverity.Error))
          gaps.Add("COMPILATION_ERRORS_OR_MISSING_REFERENCES");
      if (facts.Count >= MaxFacts) break;
    }
    Console.Write(JsonSerializer.Serialize(new Result("archlens.dotnet-semantics.v1", "PARTIAL", facts, gaps.Distinct().ToList()),
                                       new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase }));
}
catch (Exception)
{
    Console.Error.Write("ROSLYN_FAILED");
    Environment.ExitCode = 2;
}

record Source(string Path, string Text, string Sha256, string? Project);
record ProjectDocument(string Path, string Text, string Sha256);
record Request(List<Source> Sources, List<ProjectDocument>? Projects);
record Fact(string Id, string Kind, string Path, string? Project, string Sha256, int Line, int Column,
            int EndLine, int EndColumn, int StartByte, int EndByte,
            string Symbol, string? Target, string Binding);
record Result(string SchemaVersion, string Status, List<Fact> Facts, List<string> Gaps);

record ProjectConfig(string AssemblyName, LanguageVersion? ParsedLanguage, List<string> References,
    bool HasConditions, bool ImplicitUsings, bool UseWpf, bool UseWindowsForms, HashSet<string> RemovedUsings)
{
    public string? SyntheticUsings()
    {
        if (!ImplicitUsings) return null;
        var namespaces = new HashSet<string>(StringComparer.Ordinal)
        {
            "System", "System.Collections.Generic", "System.IO", "System.Linq",
            "System.Net.Http", "System.Threading", "System.Threading.Tasks"
        };
        if (UseWpf) { namespaces.Remove("System.IO"); namespaces.Remove("System.Net.Http"); }
        if (UseWindowsForms) { namespaces.Add("System.Drawing"); namespaces.Add("System.Windows.Forms"); }
        namespaces.ExceptWith(RemovedUsings);
        return string.Join("\n", namespaces.Order(StringComparer.Ordinal).Select(x => $"global using {x};"));
    }
    public static ProjectConfig Read(ProjectDocument document, List<string> gaps)
    {
        var raw = Encoding.UTF8.GetBytes(document.Text);
        if (raw.Length > 2_000_000 ||
            !Convert.ToHexStringLower(SHA256.HashData(raw)).Equals(document.Sha256, StringComparison.Ordinal))
            throw new InvalidDataException("Project hash mismatch");
        using var reader = XmlReader.Create(new StringReader(document.Text.TrimStart('\uFEFF')), new XmlReaderSettings
        {
            DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null, MaxCharactersInDocument = 2_000_000
        });
        var xml = XDocument.Load(reader);
        if (xml.Root?.Name.LocalName != "Project") throw new InvalidDataException("Invalid project declaration");
        var elements = xml.Root.Descendants().ToList();
        bool conditions = elements.Any(x => x.Attributes().Any(a => a.Name.LocalName == "Condition"));
        if (elements.Any(x => x.Name.LocalName is "Import" or "Choose"))
        {
            conditions = true;
            gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED");
        }
        string? Property(string name) => elements.Where(x => x.Name.LocalName == name &&
            !x.AncestorsAndSelf().Any(a => a.Attribute("Condition") is not null))
            .Select(x => x.Value.Trim()).LastOrDefault();
        var language = Property("LangVersion");
        LanguageVersion? parsedLanguage = language switch
        {
            "10.0" => LanguageVersion.CSharp10, "11.0" => LanguageVersion.CSharp11,
            "12.0" => LanguageVersion.CSharp12, "13.0" => LanguageVersion.CSharp13,
            "14.0" => LanguageVersion.CSharp14, "preview" => LanguageVersion.Preview,
            "latest" => LanguageVersion.Latest, _ => null
        };
        var references = new List<string>();
        foreach (var item in elements.Where(x => x.Name.LocalName == "ProjectReference"))
        {
            if (item.AncestorsAndSelf().Any(x => x.Attribute("Condition") is not null))
            {
                gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED");
                continue;
            }
            var include = (string?)item.Attribute("Include");
            if (include is null) { gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED"); continue; }
            foreach (var value in include.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                var path = RelativeProject(document.Path, value);
                if (path is null) gaps.Add("PROJECT_REFERENCES_NOT_RESOLVED");
                else references.Add(path);
            }
        }
        var name = Property("AssemblyName");
        if (string.IsNullOrWhiteSpace(name) || name.Contains("$(", StringComparison.Ordinal))
            name = Path.GetFileNameWithoutExtension(document.Path);
        var removedUsings = elements.Where(x => x.Name.LocalName == "Using" &&
            !x.AncestorsAndSelf().Any(a => a.Attribute("Condition") is not null))
            .Select(x => (string?)x.Attribute("Remove")).Where(x => !string.IsNullOrWhiteSpace(x))
            .Select(x => x!).ToHashSet(StringComparer.Ordinal);
        bool Enabled(string property) => Property(property) is "true" or "True" or "enable" or "Enable";
        return new ProjectConfig(name, parsedLanguage, references.Distinct(StringComparer.Ordinal).ToList(), conditions,
            Enabled("ImplicitUsings"), Enabled("UseWPF"), Enabled("UseWindowsForms"), removedUsings);
    }
    private static string? RelativeProject(string project, string include)
    {
        include = include.Replace('\\', '/');
        if (include.StartsWith('/') || include.Contains('$') || include.Contains('*') || include.Contains('?') ||
            !include.EndsWith(".csproj", StringComparison.OrdinalIgnoreCase)) return null;
        var parts = new List<string>();
        var slash = project.LastIndexOf('/');
        if (slash >= 0) parts.AddRange(project[..slash].Split('/'));
        foreach (var part in include.Split('/'))
        {
            if (part is "" or ".") continue;
            if (part == "..") { if (parts.Count == 0) return null; parts.RemoveAt(parts.Count - 1); }
            else parts.Add(part);
        }
        return string.Join('/', parts);
    }
}
