package io.archlens.migration;

import io.archlens.contract.Json;
import io.archlens.parser.SourceText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static io.archlens.contract.ContractException.require;

/**
 * Local source registry for migration requests.
 *
 * <p>The request and checkpoint contain only registry IDs. The resolver keeps the absolute
 * local locator and file manifest in a machine-local context file; credentials remain
 * references resolved from the host environment and are never serialized here.</p>
 */
public final class MigrationSourceRegistry {
    public static final String VERSION = "archlens.migration-source.v1";
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,99}");
    private static final long MAX_TOTAL_BYTES = 50_000_000L;

    public record Entry(String path, String sha256, long bytes) {
        public Entry {
            require(path != null && !path.isBlank() && path.length() <= 1000, "MIG_SOURCE_INVALID", "Invalid source path");
            require(sha256 != null && sha256.matches("[0-9a-f]{64}"), "MIG_SOURCE_INVALID", "Invalid source hash");
            require(bytes >= 0 && bytes <= SourceText.MAX_BYTES, "MIG_SOURCE_INVALID", "Invalid source size");
        }
    }

    public record Observed(String product, String version, String source) {
        public Observed {
            require(product != null && !product.isBlank() && product.length() <= 100, "MIG_PROFILE_INVALID", "Invalid observed product");
            require(version == null || version.length() <= 100, "MIG_PROFILE_INVALID", "Invalid observed version");
            require(source != null && !source.isBlank() && source.length() <= 1000, "MIG_PROFILE_INVALID", "Invalid observation source");
        }
    }

    public record Binding(String schemaVersion, String sourceRef, MigrationRequest.SourceKind kind,
                          String declaredProduct, String declaredVersion, String authorizationRef,
                          String credentialRef, String manifestHash, int fileCount, long bytes,
                          List<Entry> files, List<Observed> observed, String registeredAt) {
        public Binding {
            files = List.copyOf(files);
            observed = List.copyOf(observed);
        }
    }

    private record Stored(String schemaVersion, String sourceRef, MigrationRequest.SourceKind kind,
                          String sourceRoot, String locator, String declaredProduct, String declaredVersion,
                          String authorizationRef, String credentialRef, String manifestHash,
                          int fileCount, long bytes, List<Entry> files, List<Observed> observed,
                          String registeredAt) {}

    public record Resolved(Binding binding, Path sourceRoot, List<String> gaps) {
        public Resolved {
            gaps = List.copyOf(gaps);
        }
    }

    private MigrationSourceRegistry() {}

    public static Path directory(Map<String, String> env) {
        String configured = env.get("ARCHLENS_MIGRATION_SOURCE_DIR");
        return (configured == null || configured.isBlank()
                ? Path.of(".local", "migration-sources")
                : Path.of(configured)).toAbsolutePath().normalize();
    }

    /**
     * Registers one source identity. Re-registering the same manifest is idempotent;
     * changing it under the same sourceRef is rejected rather than silently widening scope.
     */
    public static Binding register(Path directory, String sourceRef, MigrationRequest.SourceKind kind,
                                   Path sourceRoot, String locator, List<String> files,
                                   String declaredProduct, String declaredVersion,
                                   String authorizationRef, String credentialRef) throws IOException {
        require(directory != null, "MIG_SOURCE_INVALID", "Source registry directory is required");
        require(sourceRef != null && ID.matcher(sourceRef).matches(), "MIG_SOURCE_INVALID", "Invalid sourceRef");
        require(kind != null, "MIG_SOURCE_INVALID", "Source kind is required");
        require(declaredProduct != null && !declaredProduct.isBlank() && declaredProduct.length() <= 100,
                "MIG_SOURCE_INVALID", "Declared product is required");
        require(declaredVersion == null || (!declaredVersion.isBlank() && declaredVersion.length() <= 100),
                "MIG_SOURCE_INVALID", "Invalid declared version");
        require(authorizationRef != null && ID.matcher(authorizationRef).matches(),
                "MIG_AUTHORIZATION_REQUIRED", "Authorization reference is required");

        List<Entry> entries = new ArrayList<>();
        Map<String, SourceText> collected = new TreeMap<>();
        List<Observed> observed = new ArrayList<>();
        long total = 0;
        Path root = null;
        String locatorHash;

        if (kind == MigrationRequest.SourceKind.BUSINESS_DATABASE) {
            require(locator != null && !locator.isBlank() && locator.length() <= 1000,
                    "MIG_LOCATOR_REQUIRED", "Database locator reference is required");
            require(credentialRef != null && ID.matcher(credentialRef).matches(),
                    "MIG_CREDENTIAL_REF_REQUIRED", "Database source requires a credential reference");
            require(files == null || files.isEmpty(), "MIG_SOURCE_FILES_INVALID", "Database source cannot carry code files");
            locatorHash = Json.hashParts("database", locator);
        } else if (kind == MigrationRequest.SourceKind.GIT_CODE) {
            throw new io.archlens.contract.ContractException("MIG_SOURCE_KIND_UNSUPPORTED",
                    "Git source acquisition is not implemented; register a local checkout instead");
        } else {
            require(sourceRoot != null && sourceRoot.isAbsolute(), "SOURCE_ROOT_INVALID", "Absolute source root required");
            root = sourceRoot.toRealPath();
            require(Files.isDirectory(root), "SOURCE_ROOT_INVALID", "Source root must be a directory");
            require(credentialRef == null, "MIG_CREDENTIAL_NOT_APPLICABLE", "Code sources cannot carry credentials");
            require(files != null && !files.isEmpty() && files.size() <= 1000,
                    "MIG_SOURCE_REQUIRED", "At least one explicit source file is required");
            Set<String> seen = new HashSet<>();
            for (String file : new TreeSet<>(files)) {
                require(file != null && !file.isBlank() && !Path.of(file).isAbsolute()
                                && !Path.of(file).normalize().startsWith("..") && !file.contains(":"),
                        "PATH_OUTSIDE_ROOT", "Source must be a relative path inside the authorized root");
                SourceText text = SourceText.read(root, file);
                require(seen.add(text.path()), "MIG_SOURCE_DUPLICATE", "Duplicate source file");
                require(total <= MAX_TOTAL_BYTES - text.location().byteLength(),
                        "MIG_SOURCE_LIMIT", "Source manifest exceeds the byte budget");
                total += text.location().byteLength();
                collected.put(text.path(), text);
                entries.add(new Entry(text.path(), text.hash(), text.location().byteLength()));
            }
            observed.addAll(observe(collected.values()));
            locatorHash = Json.hashParts("local", root.toString());
        }

        String manifestHash = Json.hashParts(VERSION, sourceRef, kind, locatorHash,
                declaredProduct, declaredVersion, authorizationRef, credentialRef, entries);
        String now = Instant.now().toString();
        Stored candidate = new Stored(VERSION, sourceRef, kind,
                root == null ? null : root.toString(), locator, declaredProduct, declaredVersion,
                authorizationRef, credentialRef, manifestHash, entries.size(), total,
                List.copyOf(entries), List.copyOf(observed), now);

        Files.createDirectories(directory);
        Path target = directory.resolve(sourceRef + ".json");
        try {
            Files.writeString(target, Json.canonical(candidate), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return binding(candidate);
        } catch (FileAlreadyExistsException exists) {
            Stored existing = read(target);
            require(existing.manifestHash().equals(manifestHash) && existing.kind() == kind
                            && Objects.equals(existing.authorizationRef(), authorizationRef),
                    "MIG_SOURCE_CONFLICT", "Source reference is already bound to another manifest or authorization");
            return binding(existing);
        }
    }

    /**
     * Resolves one request source and re-checks the registered manifest. A path is never
     * accepted from the model or from the serialized request.
     */
    public static Resolved resolve(Path directory, String sourceRef, MigrationRequest request,
                                   Map<String, String> env) throws IOException {
        require(request != null, "MIG_REQUEST_REQUIRED", "Migration request is required");
        MigrationRequest.SourceRef requested = request.sourceRefs().stream()
                .filter(item -> item.sourceRef().equals(sourceRef)).findFirst()
                .orElseThrow(() -> new io.archlens.contract.ContractException(
                        "MIG_SOURCE_UNKNOWN", "Source is not part of the request"));
        Stored stored = read(directory.resolve(sourceRef + ".json"));
        require(stored.kind() == requested.kind(), "MIG_SOURCE_KIND_CONFLICT", "Requested source kind differs");
        require(request.policyRefs().authorizationRef().equals(stored.authorizationRef()),
                "MIG_AUTHORIZATION_MISMATCH", "Authorization reference does not match the registered source");
        require(Objects.equals(requested.credentialRef(), stored.credentialRef()),
                "MIG_CREDENTIAL_REF_MISMATCH", "Credential reference does not match the registered source");
        if (requested.declaredProduct() != null) {
            require(requested.declaredProduct().equalsIgnoreCase(stored.declaredProduct()),
                    "MIG_SOURCE_PROFILE_CONFLICT", "Declared product differs from the registered source");
        }
        if (requested.declaredVersion() != null && stored.declaredVersion() != null) {
            require(requested.declaredVersion().equalsIgnoreCase(stored.declaredVersion()),
                    "MIG_SOURCE_PROFILE_CONFLICT", "Declared version differs from the registered source");
        }

        if (stored.kind() == MigrationRequest.SourceKind.BUSINESS_DATABASE) {
            require(credentialAvailable(stored.credentialRef(), env),
                    "MIG_CREDENTIAL_UNAVAILABLE", "Credential reference cannot be resolved on this host");
            return new Resolved(binding(stored), null, List.of("MIG_SOURCE_NOT_CONNECTED_IN_T09"));
        }

        Path root = Path.of(stored.sourceRoot()).toRealPath();
        require(Files.isDirectory(root), "SOURCE_ROOT_INVALID", "Registered source root is no longer a directory");
        for (Entry entry : stored.files()) {
            try {
                SourceText current = SourceText.read(root, entry.path());
                require(current.hash().equals(entry.sha256()), "MIG_SOURCE_DRIFT",
                        "Registered source bytes changed");
            } catch (NoSuchFileException | AccessDeniedException e) {
                throw new io.archlens.contract.ContractException("MIG_SOURCE_UNAVAILABLE",
                        "Registered source is no longer readable");
            }
        }
        List<String> gaps = new ArrayList<>();
        boolean observedDeclared = stored.observed().stream()
                .anyMatch(item -> normalized(item.product()).equals(normalized(stored.declaredProduct())));
        if (!observedDeclared) gaps.add("MIG_SOURCE_PROFILE_UNOBSERVED");
        for (Observed item : stored.observed()) {
            if (!normalized(item.product()).equals(normalized(stored.declaredProduct()))) continue;
            if (item.version() != null && stored.declaredVersion() != null
                    && !item.version().equalsIgnoreCase(stored.declaredVersion())) {
                throw new io.archlens.contract.ContractException("MIG_SOURCE_PROFILE_CONFLICT",
                        "Observed source version conflicts with the declaration");
            }
        }
        return new Resolved(binding(stored), root, gaps);
    }

    private static Stored read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new io.archlens.contract.ContractException("MIG_SOURCE_UNKNOWN", "Source is not registered");
        }
        return Json.MAPPER.readValue(file.toFile(), Stored.class);
    }

    private static Binding binding(Stored stored) {
        return new Binding(stored.schemaVersion(), stored.sourceRef(), stored.kind(),
                stored.declaredProduct(), stored.declaredVersion(), stored.authorizationRef(),
                stored.credentialRef(), stored.manifestHash(), stored.fileCount(), stored.bytes(),
                stored.files(), stored.observed(), stored.registeredAt());
    }

    private static boolean credentialAvailable(String credentialRef, Map<String, String> env) {
        if (credentialRef == null) return false;
        String key = "ARCHLENS_MIGRATION_CREDENTIAL_" + credentialRef.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_");
        String value = env.get(key);
        return value != null && !value.isBlank();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static List<Observed> observe(Collection<SourceText> sources) {
        List<Observed> result = new ArrayList<>();
        for (SourceText source : sources) {
            String lower = source.path().toLowerCase(Locale.ROOT);
            try {
                if (lower.endsWith(".csproj")) {
                    Element project = xml(source);
                    for (String name : List.of("TargetFramework", "TargetFrameworkVersion")) {
                        NodeList nodes = elements(project, name);
                        for (int i = 0; i < nodes.getLength(); i++) {
                            Element element = (Element) nodes.item(i);
                            if (element.hasAttribute("Condition")) continue;
                            addFramework(result, element.getTextContent().trim(), source.path());
                        }
                    }
                    NodeList langs = elements(project, "LangVersion");
                    for (int i = 0; i < langs.getLength(); i++) {
                        Element element = (Element) langs.item(i);
                        if (!element.hasAttribute("Condition")) {
                            result.add(new Observed("C#", element.getTextContent().trim(), source.path()));
                        }
                    }
                } else if (lower.endsWith("pom.xml")) {
                    Element project = xml(source);
                    for (String name : List.of("java.version", "maven.compiler.release", "maven.compiler.source")) {
                        NodeList nodes = elements(project, name);
                        for (int i = 0; i < nodes.getLength(); i++) {
                            result.add(new Observed("Java", nodes.item(i).getTextContent().trim(), source.path()));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Observation is advisory; T10 records unobserved/unsupported profiles as gaps.
            }
        }
        return result.stream().distinct().limit(64).toList();
    }

    private static void addFramework(List<Observed> result, String value, String source) {
        if (value == null || value.isBlank()) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("netcoreapp")) {
            result.add(new Observed(".NET Core", lower.substring("netcoreapp".length()), source));
        } else if (lower.startsWith("netstandard")) {
            result.add(new Observed(".NET Standard", lower.substring("netstandard".length()), source));
        } else if (lower.matches("net(?:[5-9]|[1-9][0-9]+)\\.[0-9]+")) {
            result.add(new Observed(".NET", lower.substring(3), source));
        } else if (lower.matches("net[1-4][0-9]*")) {
            String digits = lower.substring(3);
            String version = digits.length() < 2 ? digits
                    : digits.charAt(0) + "." + String.join(".", digits.substring(1).split(""));
            result.add(new Observed(".NET Framework", version, source));
        } else if (value.startsWith("v") && value.substring(1).matches("[1-4]\\.[0-9]+(?:\\.\\d+)?")) {
            result.add(new Observed(".NET Framework", value.substring(1), source));
        }
    }

    private static Element xml(SourceText source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        String text = source.text().startsWith("\uFEFF") ? source.text().substring(1) : source.text();
        return factory.newDocumentBuilder().parse(
                new org.xml.sax.InputSource(new java.io.StringReader(text))).getDocumentElement();
    }

    /** Legacy MSBuild files often use a namespace, while SDK-style files usually do not. */
    private static NodeList elements(Element root, String name) {
        NodeList namespaced = root.getElementsByTagNameNS("*", name);
        return namespaced.getLength() == 0 ? root.getElementsByTagName(name) : namespaced;
    }
}
