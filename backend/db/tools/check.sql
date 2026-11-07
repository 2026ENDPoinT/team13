-- astra 스키마 점검(db/README.md 5절). 통과 조건: 'tables', 'fks' 두 줄만 출력되고 그 밖의 행이 없다.
SELECT 'tables', COUNT(*) FROM information_schema.tables WHERE table_schema='astra';
SELECT 'fks', COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='astra';
SELECT 'dangling_fk', rc.table_name, rc.constraint_name, rc.referenced_table_name
  FROM information_schema.referential_constraints rc
  LEFT JOIN information_schema.tables t ON t.table_schema='astra' AND t.table_name=rc.referenced_table_name
 WHERE rc.constraint_schema='astra' AND t.table_name IS NULL;
SELECT 'fk_bad_col', k.table_name, k.column_name, k.referenced_table_name, k.referenced_column_name
  FROM information_schema.key_column_usage k
  LEFT JOIN information_schema.columns c ON c.table_schema='astra' AND c.table_name=k.referenced_table_name AND c.column_name=k.referenced_column_name
 WHERE k.table_schema='astra' AND k.referenced_table_name IS NOT NULL AND c.column_name IS NULL;
SELECT 'no_table_comment', table_name FROM information_schema.tables WHERE table_schema='astra' AND table_comment='';
SELECT 'no_col_comment', table_name, column_name FROM information_schema.columns WHERE table_schema='astra' AND column_comment='';
SELECT 'isolated_table', t.table_name FROM information_schema.tables t
 WHERE t.table_schema='astra'
   AND NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage k WHERE k.table_schema='astra' AND k.referenced_table_name IS NOT NULL AND (k.table_name=t.table_name OR k.referenced_table_name=t.table_name));
