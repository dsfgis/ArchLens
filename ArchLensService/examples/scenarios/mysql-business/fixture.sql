-- 仅用于本次创建的隔离合成 MySQL 容器；应用不会执行本文件。
CREATE DATABASE archlens_fixture CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE TABLE archlens_fixture.customers (id BIGINT PRIMARY KEY,name VARCHAR(100) NOT NULL,internal_note VARCHAR(100) DEFAULT 'SYNTHETIC_DEFAULT_NOT_FOR_REPORT');
CREATE TABLE archlens_fixture.orders (id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,customer_id BIGINT NOT NULL,amount DECIMAL(18,2) NOT NULL,created_at DATETIME NOT NULL,CONSTRAINT fk_customer FOREIGN KEY(customer_id) REFERENCES archlens_fixture.customers(id),UNIQUE KEY uq_customer_created(customer_id,created_at));
INSERT INTO archlens_fixture.customers VALUES(1,'SYNTHETIC_BUSINESS_ROW_NOT_FOR_REPORT',DEFAULT);
INSERT INTO archlens_fixture.orders(customer_id,amount,created_at) VALUES(1,123.45,'2026-09-24 00:00:00');
CREATE VIEW archlens_fixture.order_totals AS SELECT customer_id,SUM(amount) AS total FROM archlens_fixture.orders GROUP BY customer_id;
ALTER TABLE archlens_fixture.customers MODIFY internal_note VARCHAR(100) DEFAULT 'SYNTHETIC_DEFAULT_NOT_FOR_REPORT' COMMENT 'SYNTHETIC_COLUMN_COMMENT_NOT_FOR_REPORT';
ALTER TABLE archlens_fixture.customers COMMENT='SYNTHETIC_TABLE_COMMENT_NOT_FOR_REPORT';
ALTER TABLE archlens_fixture.customers ADD CONSTRAINT ck_customer_name CHECK (CHAR_LENGTH(name)>0);
CREATE PROCEDURE archlens_fixture.fixture_probe(IN p INT) SELECT p;
CREATE TRIGGER archlens_fixture.customer_before_insert BEFORE INSERT ON archlens_fixture.customers FOR EACH ROW SET NEW.name=NEW.name;
CREATE DATABASE archlens_outside;
CREATE TABLE archlens_outside.out_of_scope(secret_value VARCHAR(100));
CREATE USER 'archlens_reader'@'%' IDENTIFIED BY 'synthetic-readonly-password';
GRANT SELECT,SHOW VIEW ON archlens_fixture.* TO 'archlens_reader'@'%';
CREATE USER 'archlens_limited'@'%' IDENTIFIED BY 'synthetic-readonly-password';
GRANT SELECT ON archlens_fixture.customers TO 'archlens_limited'@'%';
