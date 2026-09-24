package sample;
import javax.servlet.Filter;
import javax.xml.parsers.DocumentBuilder;
// javax.xml 属于 JDK，这个导入不能被全局替换规则误判。
public abstract class LegacyFilter implements Filter { DocumentBuilder builder; }
