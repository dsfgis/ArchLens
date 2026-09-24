package io.archlens.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.archlens.contract.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.*;
import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import static io.archlens.contract.Model.*;

/** Deliberately small PAR01/PAR02 slice. Every unsupported construct is a diagnostic. */
public final class MapperAnalyzer {
    public static final String PRODUCER="archlens-mapper-0.1/javaparser-3.27.1/jsqlparser-5.3";
    private final List<NodeIR> nodes=new ArrayList<>();
    private final List<EdgeIR> edges=new ArrayList<>();
    private final List<EvidenceIR> evidence=new ArrayList<>();
    private final List<DiagnosticIR> diagnostics=new ArrayList<>();
    private Scope scope;
    private OfflineRequest input;
    private SourceText mapper;

    public GraphDocument analyze(Path root,OfflineRequest input) throws Exception {
        // Instance state is scoped to one call; no shared mutable analyzer singleton.
        nodes.clear(); edges.clear(); evidence.clear(); diagnostics.clear();
        this.input=input; scope=new Scope(input.projectId(),input.snapshotId());
        SourceText java=SourceText.read(root,input.javaFile()); mapper=SourceText.read(root,input.mapperFile());
        String catalogHash=Json.hash(input.catalog());
        for(var column:input.catalog()) {
            String key=columnKey(input,column);
            nodes.add(NodeIR.create(scope,input.databaseSourceId(),NodeType.COLUMN,key,column.name(),
                    new SourceRef(catalogHash,Location.metadata(key)),Map.of("dbType",column.type(),"nullable",column.nullable(),
                            "identityMeaning",column.identityMeaning(),"schema",column.schema(),"table",column.table(),
                            "databaseKey",input.databaseKey(),"origin","OFFLINE_CATALOG")));
        }
        diagnostics.add(DiagnosticIR.create(scope,input.databaseSourceId(),"OFFLINE_CATALOG_UNVERIFIED",
                "Catalog was supplied as offline input; runtime database and repository alignment are not verified",
                Location.metadata(input.databaseKey()),"OFFLINE_CATALOG"));
        EvidenceIR javaEvidence=EvidenceIR.create(scope,input.repositorySourceId(),java.hash(),"JAVA_DECLARATION",PRODUCER,java.location(),snippet(java));
        EvidenceIR sqlEvidence=EvidenceIR.create(scope,input.repositorySourceId(),mapper.hash(),"MYBATIS_SQL",PRODUCER,mapper.location(),snippet(mapper));
        evidence.add(javaEvidence); evidence.add(sqlEvidence);
        var parsed=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(java.text());
        if(!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            diag("JAVA_PARSE_ERROR","Java source did not parse",java.location()); return document();
        }
        var unit=parsed.getResult().orElseThrow();
        List<ClassOrInterfaceDeclaration> declarations=unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(d->d.getFullyQualifiedName().orElse("").equals(input.namespace()) && d.isInterface()).toList();
        if(declarations.size()!=1) { diag("AMBIGUOUS_BINDING","Expected exactly one declared mapper interface matching namespace",java.location()); return document(); }
        var declaration=declarations.getFirst();
        if(!declaration.getExtendedTypes().isEmpty()) diag("UNSUPPORTED_INHERITANCE","Inherited mapper methods are not resolved",java.location());
        Map<String,List<NodeIR>> methods=new HashMap<>();
        for(var method:declaration.getMethods()) {
            if(method.getAnnotations().stream().anyMatch(a->Set.of("Select","SelectProvider","Insert","InsertProvider","Update","UpdateProvider","Delete","DeleteProvider")
                    .contains(a.getName().getIdentifier()))) {
                diag("AMBIGUOUS_BINDING","Annotated SQL and XML bindings require precedence analysis: "+method.getNameAsString(),java.location()); continue;
            }
            if(!method.getParameters().isEmpty() || method.getBody().isPresent() || !method.getTypeParameters().isEmpty()) {
                diag("UNSUPPORTED_METHOD","Only direct abstract zero-argument mapper methods are supported: "+method.getNameAsString(),java.location()); continue;
            }
            String signature=input.namespace()+"#"+method.getNameAsString()+"()";
            var unresolved=DiagnosticIR.create(scope,input.repositorySourceId(),"UNRESOLVED_RETURN_TYPE",
                    "Classpath resolution is not implemented for "+signature,java.location(),"JAVA_DECLARATION_V0_1");
            diagnostics.add(unresolved);
            Map<String,Object> attributes=new HashMap<>();
            attributes.put("signature",signature); attributes.put("declaringType",input.namespace()); attributes.put("parameterTypes",List.of());
            attributes.put("returnType",null); attributes.put("returnTypeSyntax",method.getTypeAsString()); attributes.put("module",input.module());
            attributes.put("diagnosticId",unresolved.diagnosticId());
            NodeIR node=NodeIR.create(scope,input.repositorySourceId(),NodeType.METHOD,input.repositorySourceId()+"/"+input.module()+"/"+signature,
                    method.getNameAsString(),new SourceRef(java.hash(),java.location()),attributes);
            nodes.add(node); methods.computeIfAbsent(method.getNameAsString(),x->new ArrayList<>()).add(node);
        }
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        Element xml;
        try {
            var builder=factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            xml=builder.parse(new InputSource(new StringReader(mapper.text()))).getDocumentElement();
        } catch(org.xml.sax.SAXException e) { diag("XML_REJECTED","Malformed XML or forbidden DOCTYPE/entity declaration",mapper.location()); return document(); }
        if(!xml.getTagName().equals("mapper") || !xml.getAttribute("namespace").equals(input.namespace())) {
            diag("AMBIGUOUS_BINDING","XML namespace does not match the declared interface",mapper.location()); return document();
        }
        Map<String,Integer> statementCounts=new HashMap<>();
        for(int i=0;i<xml.getChildNodes().getLength();i++) if(xml.getChildNodes().item(i) instanceof Element e)
            if(Set.of("select","insert","update","delete").contains(e.getTagName())) statementCounts.merge(e.getAttribute("id"),1,Integer::sum);
        for(int i=0;i<xml.getChildNodes().getLength();i++) {
            if(!(xml.getChildNodes().item(i) instanceof Element statement)) continue;
            if(!statement.getTagName().equals("select")) { diag("UNSUPPORTED_MAPPING","Only static select mappings are supported",mapper.location()); continue; }
            String id=statement.getAttribute("id");
            if(statementCounts.getOrDefault(id,0)!=1 || statement.hasAttribute("databaseId") || methods.getOrDefault(id,List.of()).size()!=1) {
                diag("AMBIGUOUS_BINDING","Missing, overloaded or database-specific mapper method: "+id,mapper.location()); continue;
            }
            if(statement.getElementsByTagName("*").getLength()!=0 || statement.getTextContent().contains("${")) {
                diag("DYNAMIC_IDENTIFIER","Dynamic mapper SQL requires branch/source-map analysis: "+id,mapper.location()); continue;
            }
            parseSql(statement.getTextContent(),methods.get(id).getFirst(),javaEvidence,sqlEvidence);
        }
        diag("FIELD_LINEAGE_GAP","Return mapping and downstream Java/JSON/Vue consumption are not covered by this analyzer slice",mapper.location());
        return document();
    }
    private void parseSql(String sql,NodeIR method,EvidenceIR javaEvidence,EvidenceIR sqlEvidence) {
        if(sql.contains("#{")) { diag("UNSUPPORTED_PARAMETER","Parameterized statements are outside the zero-argument slice",mapper.location()); return; }
        PlainSelect select;
        try {
            var statement=CCJSqlParserUtil.parse(sql);
            if(!(statement instanceof PlainSelect plain)) { diag("UNSUPPORTED_SQL","Only one plain SELECT is supported",mapper.location()); return; }
            select=plain;
        } catch(Exception e) { diag("SQL_PARSE_ERROR","SQL did not parse as a single statement",mapper.location()); return; }
        if(!(select.getFromItem() instanceof Table table) || select.getJoins()!=null || select.getWithItemsList()!=null
                || select.getGroupBy()!=null || select.getHaving()!=null || select.getOrderByElements()!=null
                || select.getIntoTables()!=null || select.getQualify()!=null) {
            diag("UNSUPPORTED_SQL","JOIN, CTE, subquery, grouping, ordering and SELECT INTO require additional analysis",mapper.location()); return;
        }
        if(table.getDatabaseName()!=null || table.getCatalogName()!=null || table.getPivot()!=null || table.getUnPivot()!=null) {
            diag("AMBIGUOUS_BINDING","Database-qualified or transformed table is unsupported",mapper.location()); return;
        }
        String schema=table.getSchemaName()==null ? input.defaultSchema() : pgIdentifier(table.getSchemaName());
        String tableName=pgIdentifier(table.getName());
        List<net.sf.jsqlparser.schema.Column> references=new ArrayList<>();
        for(var item:select.getSelectItems()) {
            if(item.getExpression() instanceof net.sf.jsqlparser.schema.Column column) references.add(column);
            else diag("UNSUPPORTED_EXPRESSION","Only direct SELECT columns are bound; expression or wildcard is unresolved",mapper.location());
        }
        if(select.getWhere()!=null) collectWhere(select.getWhere(),references);
        Set<String> used=new HashSet<>();
        for(var column:references) {
            Table qualifierTable=column.getTable();
            if(qualifierTable!=null && (qualifierTable.getDatabaseName()!=null || qualifierTable.getCatalogName()!=null
                    || qualifierTable.getSchemaName()!=null && (table.getAlias()!=null || !pgIdentifier(qualifierTable.getSchemaName()).equals(schema)))) {
                diag("AMBIGUOUS_BINDING","Column schema/database qualifier does not match the selected table",mapper.location()); continue;
            }
            String qualifier=column.getTable()==null ? null : column.getTable().getName();
            String expected=table.getAlias()!=null ? pgIdentifier(table.getAlias().getName()) : tableName;
            if(qualifier!=null && !qualifier.isBlank() && !pgIdentifier(qualifier).equals(expected)) {
                diag("AMBIGUOUS_BINDING","Column qualifier does not match table/alias",mapper.location()); continue;
            }
            String name=pgIdentifier(column.getColumnName());
            List<OfflineRequest.Column> matches=input.catalog().stream().filter(c->c.schema().equals(schema) && c.table().equals(tableName) && c.name().equals(name)).toList();
            if(matches.size()!=1) { diag("UNRESOLVED_COLUMN","Catalog does not uniquely contain "+schema+"."+tableName+"."+name,mapper.location()); continue; }
            String key=columnKey(input,matches.getFirst());
            String target=Model.nodeId(scope,NodeType.COLUMN,key);
            if(used.add(target)) edges.add(EdgeIR.create(scope,method.nodeId(),target,EdgeKind.READS,method.attributes().get("signature")+"/"+name,
                    List.of(javaEvidence.evidenceId(),sqlEvidence.evidenceId()),Certainty.CONFIRMED));
        }
    }
    private void collectWhere(Expression expression,List<net.sf.jsqlparser.schema.Column> columns) {
        if(expression instanceof net.sf.jsqlparser.schema.Column c) columns.add(c);
        else if(expression instanceof BinaryExpression b) { collectWhere(b.getLeftExpression(),columns); collectWhere(b.getRightExpression(),columns); }
        else if(!(expression instanceof StringValue || expression instanceof LongValue || expression instanceof DoubleValue
                || expression instanceof NullValue || expression instanceof JdbcParameter))
            diag("UNSUPPORTED_EXPRESSION","WHERE expression is not fully covered",mapper.location());
    }
    static String pgIdentifier(String name) {
        if(name.startsWith("\"") && name.endsWith("\"")) return name.substring(1,name.length()-1).replace("\"\"","\"");
        return name.toLowerCase(Locale.ROOT);
    }
    public static String columnKey(OfflineRequest input,OfflineRequest.Column c) {
        // JSON component encoding prevents slash-containing identifiers from colliding.
        return input.databaseSourceId()+"/"+Json.canonical(List.of(input.databaseKey(),c.schema(),c.table(),c.name()));
    }
    private void diag(String code,String message,Location location) {
        diagnostics.add(DiagnosticIR.create(scope,input.repositorySourceId(),code,message,location,"MAPPER_STATIC_SELECT_V0_1"));
    }
    private GraphDocument document() {
        return new GraphDocument(VERSION,scope.projectId(),scope.snapshotId(),nodes,edges,evidence,
                diagnostics.stream().collect(java.util.stream.Collectors.toMap(DiagnosticIR::diagnosticId,d->d,(a,b)->a,TreeMap::new)).values().stream().toList());
    }
    private static String snippet(SourceText text) { return text.text().length()<=12000 ? text.text() : null; }
}
