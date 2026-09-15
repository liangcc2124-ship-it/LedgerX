ALTER TABLE migration_probe ADD COLUMN broken_value TEXT NULL;

INSERT INTO missing_table(id) VALUES (1);
