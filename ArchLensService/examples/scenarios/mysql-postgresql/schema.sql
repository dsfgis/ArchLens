-- 合成结构：只作为调查输入，不执行此 SQL。
CREATE TABLE `orders` (
  id BIGINT UNSIGNED AUTO_INCREMENT,
  attempts INT,
  amount DECIMAL(12,2)
);
SELECT IFNULL(amount, 0) FROM `orders`;
