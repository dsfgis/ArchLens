package io.archlens.investigation.rules;

import io.archlens.parser.SourceText;
import static io.archlens.investigation.rules.Lexical.*;
import static io.archlens.investigation.rules.ScenarioRules.*;

/** C# 首批特征调查：区分确认的关键字与未绑定的上下文/属性候选，不宣称跨语言行为等价。 */
final class CSharpRules {
    static void analyze(SourceText source,Context c) {
        var tokens=scan(source.text(),Mode.CSHARP);
        for(int i=0;i<tokens.size();i++) {
            c.check();Token t=tokens.get(i);
            // C# 大小写敏感；@ 转义标识符及字符串中的同名文本不能匹配为关键字。
            if(t.kind()!=Kind.WORD)continue;
            switch(t.value()) {
                case "decimal" -> c.feature(source,t,"CS_DECIMAL","INCOMPATIBLE","C# decimal 原生类型/运算不能原样编译为 Java 21。","评估 BigDecimal；显式确定精度、舍入、除法、相等和 JSON 数字契约；不要直接替换为 double。",null);
                case "uint","ulong" -> c.feature(source,t,"CS_UNSIGNED","INCOMPATIBLE","Java 21 没有同名的 uint/ulong 基础类型。","验证上界与溢出语义；评估更宽整数、无符号运算 API 或 BigInteger。",null);
                case "await" -> c.feature(source,t,"CS_AWAIT","UNKNOWN","发现 await 词法候选，尚未绑定异步调用或状态机。","确认其为异步表达式后设计 CompletableFuture/执行器等方案；验证取消、异常、上下文和调用次序。","词法扫描无法排除上下文标识符，也不能证明异步行为等价");
                case "JsonPropertyName","JsonPropertyNameAttribute" -> {
                    if(i>0&&tokens.get(i-1).is("[")&&i+1<tokens.size()&&tokens.get(i+1).is("("))
                        c.feature(source,t,"CS_SERIALIZATION","UNKNOWN","发现 JsonPropertyName 属性候选；未解析其命名空间绑定。","确认 System.Text.Json 属性后，核对 Java 序列化配置、字段名、null、枚举、日期和往返样例。",null);
                }
                default -> { }
            }
        }
        c.gap("CSHARP_LEXICAL_ONLY",source.path()+": no Roslyn binding, preprocessor evaluation, resolved .NET APIs or behavioral analysis");
    }
}
