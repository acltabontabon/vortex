UPDATE executions
SET summary_json = REPLACE(summary_json, '"answer":"Yes. The service sustained ', '"answer":"The service sustained ')
WHERE summary_json LIKE '%"answer":"Yes. The service sustained %';

UPDATE executions
SET summary_json = REPLACE(summary_json, '"answer":"No. Objectives were first violated at ', '"answer":"Objectives were first violated at ')
WHERE summary_json LIKE '%"answer":"No. Objectives were first violated at %';

UPDATE executions
SET summary_json = REPLACE(summary_json, '"answer":"No. ', '"answer":"Objectives violated: ')
WHERE summary_json LIKE '%"answer":"No. %';

UPDATE executions
SET summary_json = REPLACE(summary_json, '"answer":"Undetermined. ', '"answer":"')
WHERE summary_json LIKE '%"answer":"Undetermined. %';
