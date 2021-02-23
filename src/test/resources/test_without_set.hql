CREATE DATABASE testdb;

CREATE EXTERNAL TABLE testdb.test
(
  field1 string,
  field2 string
)
STORED AS ORC;