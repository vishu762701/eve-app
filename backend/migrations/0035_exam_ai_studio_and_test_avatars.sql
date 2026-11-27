-- Additive columns for Mock Test avatars and Exam AI Question Prompt Studio.
-- Defensive PRAGMA table_info checks keep pre-0035 live D1 tables safe before migration runs.
ALTER TABLE generated_tests ADD COLUMN avatar_url TEXT NOT NULL DEFAULT '';
ALTER TABLE generated_tests ADD COLUMN avatar_emoji TEXT NOT NULL DEFAULT '';
ALTER TABLE generated_tests ADD COLUMN avatar_color INTEGER DEFAULT NULL;
ALTER TABLE exams ADD COLUMN ai_prompt TEXT NOT NULL DEFAULT '';
ALTER TABLE exams ADD COLUMN ai_language TEXT NOT NULL DEFAULT 'english';
ALTER TABLE exams ADD COLUMN ai_difficulty TEXT NOT NULL DEFAULT 'mixed';
