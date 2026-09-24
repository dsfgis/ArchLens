package io.archlens.investigation.rules;

import io.archlens.contract.ContractException;
import io.archlens.contract.Model.Location;
import io.archlens.parser.SourceText;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 有界特征词法器，不充当完整 SQL/C# 编译器。无法可靠分词的模式整文件停止匹配并报告缺口。 */
final class Lexical {
    enum Mode { MYSQL, ORACLE, CSHARP }
    enum Kind { WORD, IDENTIFIER, STRING, NUMBER, SYMBOL }
    record Token(String value,int start,int end,Kind kind) {
        boolean is(String word) {return (kind==Kind.WORD || kind==Kind.SYMBOL) && value.equalsIgnoreCase(word);}
        boolean name() {return kind==Kind.WORD || kind==Kind.IDENTIFIER;}
    }
    static List<Token> scan(String s,Mode mode) {
        if(s.length()>500_000) throw error("RULE_FILE_LIMIT");
        List<Token> out=new ArrayList<>(); int n=s.length();
        for(int i=0;i<n;) {
            if(out.size()>=50_000) throw error("RULE_TOKEN_LIMIT");
            char c=s.charAt(i);int start=i;
            if(Character.isWhitespace(c)||c=='\ufeff') {i++;continue;}
            boolean sql=mode!=Mode.CSHARP;
            if((sql && s.startsWith("--",i) && (mode!=Mode.MYSQL || i+2==n || Character.isWhitespace(s.charAt(i+2))))
                    ||(!sql && s.startsWith("//",i)) || (mode==Mode.MYSQL && c=='#')) {
                while(i<n && s.charAt(i)!='\n' && s.charAt(i)!='\r') i++;continue;
            }
            if(s.startsWith("/*",i)) {
                if(s.startsWith("/*!",i)||s.startsWith("/*+",i)) throw error("EXECUTABLE_COMMENT_UNSUPPORTED");
                int end=s.indexOf("*/",i+2);if(end<0)throw error("UNTERMINATED_COMMENT");i=end+2;continue;
            }
            if(!sql && (c=='#'||c=='$'||s.startsWith("\"\"\"",i))) throw error("CSHARP_LEXICAL_MODE_UNSUPPORTED");
            if(mode==Mode.ORACLE && (c=='q'||c=='Q') && i+1<n && s.charAt(i+1)=='\'') throw error("ORACLE_Q_QUOTE_UNSUPPORTED");
            if(sql && c=='$') throw error("SQL_TEMPLATE_UNSUPPORTED");
            boolean verbatim=!sql && s.startsWith("@\"",i);
            if(c=='\''||c=='\"'||(sql&&c=='`')||verbatim) {
                if(mode==Mode.MYSQL&&c=='\"')throw error("MYSQL_SQL_MODE_REQUIRED");
                if(mode==Mode.ORACLE&&c=='`')throw error("INVALID_SQL_QUOTE");
                if(verbatim)i++;
                char quote=s.charAt(i++); boolean closed=false;
                while(i<n) {
                    if(s.charAt(i)=='\\' && (mode==Mode.MYSQL || (!sql&&!verbatim))) {
                        if(mode==Mode.MYSQL)throw error("MYSQL_SQL_MODE_REQUIRED");
                        i+=2;continue;
                    }
                    if(s.charAt(i)==quote) {
                        if((sql||verbatim) && i+1<n&&s.charAt(i+1)==quote) {i+=2;continue;}
                        i++;closed=true;break;
                    }
                    i++;
                }
                if(!closed)throw error("UNTERMINATED_LITERAL");
                Kind k=sql && quote!='\''?Kind.IDENTIFIER:Kind.STRING;
                out.add(new Token(s.substring(start,i),start,i,k));continue;
            }
            if(c=='\\')throw error("ESCAPED_IDENTIFIER_UNSUPPORTED");
            boolean escaped=!sql&&c=='@';
            if(Character.isJavaIdentifierStart(c)||escaped) {
                i++;while(i<n&&(Character.isJavaIdentifierPart(s.charAt(i))||s.charAt(i)=='#'))i++;
                out.add(new Token(s.substring(start,i),start,i,escaped?Kind.IDENTIFIER:Kind.WORD));continue;
            }
            if(Character.isDigit(c)) {i++;while(i<n&&Character.isDigit(s.charAt(i)))i++;out.add(new Token(s.substring(start,i),start,i,Kind.NUMBER));continue;}
            out.add(new Token(String.valueOf(c),i,i+1,Kind.SYMBOL));i++;
        }
        // 注释和字符串已作为整体处理，括号检查只针对源码符号，避免把字符串内容误当语法。
        Deque<String> stack=new ArrayDeque<>();
        for(Token t:out) if(t.kind==Kind.SYMBOL) {
            if(Set.of("(","[","{").contains(t.value))stack.push(t.value);
            if(Set.of(")","]","}").contains(t.value)) {
                String expected=switch(t.value){case ")"->"(";case "]"->"[";default->"{";};
                if(stack.isEmpty()||!stack.pop().equals(expected))throw error("UNBALANCED_SOURCE");
            }
        }
        if(!stack.isEmpty())throw error("UNBALANCED_SOURCE");
        return out;
    }
    static ContractException error(String code) {return new ContractException(code,"Source outside supported feature grammar");}
    static Location location(SourceText source,int start,int end) {
        // 内部下标为 UTF-16；对外定位换算为原文 UTF-8 字节及 Unicode 码点行列，结束位置不包含。
        String s=source.text();int[] a=position(s,start),b=position(s,end);
        return new Location(source.path(),a[0],a[1],b[0],b[1],
                (long)s.substring(0,start).getBytes(StandardCharsets.UTF_8).length,
                (long)s.substring(start,end).getBytes(StandardCharsets.UTF_8).length,null);
    }
    private static int[] position(String s,int offset) {
        int line=1,column=1;
        for(int i=0;i<offset;) {
            int cp=s.codePointAt(i);i+=Character.charCount(cp);
            if(cp=='\r') {if(i<offset&&s.charAt(i)=='\n')i++;line++;column=1;}
            else if(cp=='\n'){line++;column=1;}else column++;
        }
        return new int[]{line,column};
    }
}
