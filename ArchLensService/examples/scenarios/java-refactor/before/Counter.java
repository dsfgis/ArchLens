package sample;
// 前版本：公共契约及状态的调查依据。
public class Counter {
    private int count = 1;
    public int value(int x) { return x + count; }
    public int read() { return value(1); }
}
