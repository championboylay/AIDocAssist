-- Owner's decision 2026-10-24: embeddings come from Gemini (two keys) with Ollama as the fallback.
-- Their vectors can't be compared, so each record says which model made its embedding
-- ('gemini/<model>' or 'ollama/<model>'). NULL = a record from before this file, embedded by the
-- configured Ollama model.
ALTER TABLE knowledge_records ADD COLUMN embed_model TEXT;
