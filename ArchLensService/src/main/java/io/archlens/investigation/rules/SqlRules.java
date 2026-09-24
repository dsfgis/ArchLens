package io.archlens.investigation.rules;

import io.archlens.parser.SourceText;
import java.util.*;
import static io.archlens.investigation.rules.Lexical.*;
import static io.archlens.investigation.rules.ScenarioRules.*;

/** 解析有限 CREATE TABLE 列声明；SELECT 仅做明确标注的词法特征检查，不推导表列绑定。 */
final class SqlRules {
    static void analyze(SourceText s,Family family,Context c) {
        var tokens=scan(s.text(),family==Family.MYSQL?Mode.MYSQL:Mode.ORACLE);
        // 过程体作为未覆盖整体处理，不能把内部语句拆成看似独立、已支持的 SQL。
        for(int i=0;i<tokens.size();i++) if(tokens.get(i).is("CREATE")) {
            int k=i+1;if(at(tokens,k,"OR")&&at(tokens,k+1,"REPLACE"))k+=2;
            if(k<tokens.size()&&Set.of("PACKAGE","PROCEDURE","FUNCTION","TRIGGER","TYPE").stream().anyMatch(tokens.get(k)::is)) {
                c.gap("PROCEDURAL_SQL_UNSUPPORTED",s.path());return;
            }
        }
        int start=0;
        for(int i=0;i<=tokens.size();i++) if(i==tokens.size()||tokens.get(i).is(";")) {
            c.check();var statement=tokens.subList(start,i);start=i+1;if(statement.isEmpty())continue;
            boolean ddl=at(statement,0,"CREATE")&&at(statement,1,"TABLE");
            boolean select=at(statement,0,"SELECT");
            if(!ddl&&!select){c.gap("SQL_STATEMENT_UNSUPPORTED",s.path());continue;}
            if(ddl)columns(s,statement,family,c);
            if(select)c.gap("SQL_SELECT_FEATURE_SCAN_ONLY",s.path());
            for(int j=0;j<statement.size();j++) {
                c.check();Token t=statement.get(j);
                if(family==Family.MYSQL&&t.kind()==Kind.IDENTIFIER&&t.value().startsWith("`"))
                    c.feature(s,t,"MYSQL_BACKTICK","INCOMPATIBLE","反引号标识符不能直接用于 PostgreSQL 原生 SQL。","按实际大小写使用双引号或未引用标识符；核对大小写折叠。",null);
                if(family==Family.ORACLE&&t.kind()==Kind.STRING&&t.value().equals("''"))
                    c.feature(s,t,"ORACLE_EMPTY_STRING","CONDITIONAL","空字符串在 Oracle 字符语义中视为 NULL，迁移后须保留业务约定。","检查默认值、比较、拼接和唯一约束中的空字符串/NULL 样例。","尚未核实数据分布及应用是否依赖空字符串等于 NULL 的行为");
                if(!select)continue;
                if((family==Family.MYSQL&&t.is("IFNULL")||family==Family.ORACLE&&t.is("NVL"))&&at(statement,j+1,"(")&&!at(statement,j-1,"."))
                    c.feature(s,t,family==Family.MYSQL?"MYSQL_IFNULL":"ORACLE_NVL","INCOMPATIBLE","PostgreSQL 16 原生函数集合不提供该同名空值函数。","评估 COALESCE；验证隐式类型转换、求值副作用和 NULL 样例。","结论限原生 PostgreSQL；目标是否提供兼容扩展/自定义同名函数未核实");
                if(family==Family.ORACLE&&t.is("NEXTVAL")&&at(statement,j-1,".")&&j>=2&&statement.get(j-2).name()&&!at(statement,j+1,"("))
                    c.feature(s,t,"ORACLE_NEXTVAL","INCOMPATIBLE","sequence.NEXTVAL 不是 PostgreSQL 的序列取值语法。","改造方案可使用 nextval(regclass)；核对 schema、标识符、权限和序列状态，不假设序列无间隙。",null);
            }
        }
        c.gap("SQL_RUNTIME_NOT_COLLECTED","No live catalog, data, collation, SQL modes, transactions, drivers or call graph");
    }
    private static void columns(SourceText s,List<Token> t,Family family,Context c) {
        int p=2;if(at(t,p,"IF")&&at(t,p+1,"NOT")&&at(t,p+2,"EXISTS"))p+=3;
        if(p>=t.size()||!t.get(p).name())throw error("CREATE_TABLE_GRAMMAR_UNSUPPORTED");p++;
        while(at(t,p,".")){p++;if(p>=t.size()||!t.get(p).name())throw error("CREATE_TABLE_GRAMMAR_UNSUPPORTED");p++;}
        if(!at(t,p,"("))throw error("CREATE_TABLE_GRAMMAR_UNSUPPORTED");
        int depth=1,start=++p;
        for(;p<t.size();p++) {
            if(at(t,p,"("))depth++;
            if(at(t,p,")"))depth--;
            if(depth==0||depth==1&&at(t,p,",")) {
                column(s,t.subList(start,p),family,c);start=p+1;
                if(depth==0){if(p+1<t.size())c.gap("SQL_TABLE_OPTIONS_UNCHECKED",s.path());return;}
            }
        }
        throw error("CREATE_TABLE_GRAMMAR_UNSUPPORTED");
    }
    private static void column(SourceText s,List<Token> col,Family family,Context c) {
        if(col.size()<2||!col.get(0).name()||!col.get(1).name())throw error("COLUMN_GRAMMAR_UNSUPPORTED");
        if(Set.of("PRIMARY","FOREIGN","UNIQUE","CONSTRAINT","CHECK","INDEX","KEY").stream().anyMatch(col.getFirst()::is)) {c.gap("SQL_CONSTRAINT_UNCHECKED",s.path());return;}
        Token type=col.get(1);boolean recognized=false;
        if(family==Family.MYSQL) {
            // 只检查表达式之前的列属性，避免把默认表达式内的同名标识符误判为类型属性。
            for(int i=2,depth=0;i<col.size();i++) {
                Token t=col.get(i);if(t.is("("))depth++;if(t.is(")"))depth--;
                if(depth!=0)continue;
                if(t.is("DEFAULT")||t.is("COMMENT")||t.is("GENERATED")||t.is("CHECK")||t.is("REFERENCES")){c.gap("SQL_COLUMN_EXPRESSION_UNCHECKED",s.path());break;}
                if(t.is("UNSIGNED")&&Set.of("INT","INTEGER","BIGINT","SMALLINT","TINYINT","MEDIUMINT","DECIMAL","NUMERIC","FLOAT","DOUBLE").stream().anyMatch(type::is)) {
                    recognized=true;c.feature(s,t,"MYSQL_UNSIGNED","INCOMPATIBLE","PostgreSQL 原生数值类型没有 UNSIGNED 列属性。","按源类型范围选择更宽有符号类型或 numeric，并评估非负 CHECK；特别验证 BIGINT UNSIGNED 上界。",null);
                }
                if(t.is("AUTO_INCREMENT")) {recognized=true;c.feature(s,t,"MYSQL_AUTO_INCREMENT","INCOMPATIBLE","AUTO_INCREMENT 列属性不能直接迁移到 PostgreSQL。","评估 identity；验证显式 0/NULL、导入历史 ID、下一序列值和生成键读取行为。",null);}
            }
            if((type.is("INT")||type.is("INTEGER"))&&col.size()==2) {
                recognized=true;c.feature(s,type,"MYSQL_SIGNED_INT","COMPATIBLE","仅已检查的无修饰有符号 INT 值域与 PostgreSQL integer 一致。","仍需检查默认值、约束、SQL 表达式与调用者，不代表整列/整表可迁移。","结论仅覆盖该声明的整数值域，不覆盖其他语义");
            }
        } else {
            if(type.is("NUMBER")||type.is("VARCHAR2")) {recognized=true;c.feature(s,type,"ORACLE_TYPE","INCOMPATIBLE","该 Oracle 类型名不属于 PostgreSQL 16 原生类型。","NUMBER 评估 numeric 并保留精度/标度；VARCHAR2 需验证 BYTE/CHAR 长度、字符集和空值语义。",null);}
            if(type.is("DATE")){recognized=true;c.feature(s,type,"ORACLE_DATE","CONDITIONAL","Oracle DATE 包含时分秒，直接映射 PostgreSQL date 会丢失时间语义。","保留时间时评估 timestamp without time zone；验证时间、时区及夏令时边界。","实际数据是否含非零时间、业务是否允许截断尚未核实");}
        }
        if(!recognized)c.gap("SQL_COLUMN_NO_COMPLETE_RULE",s.path()+":"+col.getFirst().start());
    }
    private static boolean at(List<Token> tokens,int i,String value){return i>=0&&i<tokens.size()&&tokens.get(i).is(value);}
}
