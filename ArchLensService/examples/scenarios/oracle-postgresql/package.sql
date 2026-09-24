-- 包体不在首批解析范围，必须报告 UNKNOWN/覆盖缺口。
CREATE OR REPLACE PACKAGE orders_api AS
  PROCEDURE submit_order;
END;
