package sample;
// 后版本：read 被改名，value 描述符保持但实现发生变化。
public class Counter {
    private int count = 2;
    public int value(int x) { return x * count; }
    public int current() { return value(1); }
}
