-- 合成结构：DATE 含时间；空字符串与 NULL 的语义需人工验证。
CREATE TABLE orders (id NUMBER(19), label VARCHAR2(40 CHAR) DEFAULT '', created_at DATE);
SELECT NVL(label, ''), orders_seq.NEXTVAL FROM orders;
