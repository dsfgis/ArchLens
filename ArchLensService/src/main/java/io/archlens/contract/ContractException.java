package io.archlens.contract;

public final class ContractException extends IllegalArgumentException {
    private final String code;
    public ContractException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public static void require(boolean condition, String code, String message) {
        if (!condition) throw new ContractException(code, message);
    }
}
