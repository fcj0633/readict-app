-- CR-0006: favorite contexts become immutable attached examples. The source-location and
-- context-key columns are deliberately removed; only the text, word offset and book title
-- snapshot remain. Existing rows are preserved.
ALTER TABLE favorite_word_context
  DROP FOREIGN KEY fk_context_book,
  DROP FOREIGN KEY fk_context_chapter,
  DROP INDEX uq_favorite_context,
  DROP INDEX ix_context_origin,
  DROP INDEX ix_context_book,
  DROP CHECK ck_context_ranges,
  DROP COLUMN book_id,
  DROP COLUMN chapter_id,
  DROP COLUMN origin_key,
  DROP COLUMN source_hash,
  DROP COLUMN artifact_hash,
  DROP COLUMN pipeline_version,
  DROP COLUMN context_key,
  DROP COLUMN spine_href,
  DROP COLUMN block_id,
  DROP COLUMN sentence_start,
  DROP COLUMN sentence_end,
  DROP COLUMN word_range_cfi,
  DROP COLUMN sentence_range_cfi,
  DROP COLUMN quote_prefix,
  DROP COLUMN quote_suffix,
  DROP COLUMN chapter_title_snapshot,
  ADD CONSTRAINT ck_example_word_range
    CHECK(word_start >= 0 AND word_end > word_start);

-- Old receipts remain replayable. Their business state is reduced to two states and no
-- context identifier is returned; request_hash is intentionally retained.
UPDATE mutation_receipt
SET context_id = NULL,
    result_json = JSON_REMOVE(
      JSON_SET(
        COALESCE(result_json, JSON_OBJECT()),
        '$.state',
        CASE
          WHEN resource_state = 'deleted' THEN 'NOT_FAVORITED'
          WHEN JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.state')) IN ('WORD', 'CONTEXT') THEN 'FAVORITED'
          WHEN JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.state')) = 'NONE' THEN 'NOT_FAVORITED'
          ELSE 'FAVORITED'
        END
      ),
      '$.contextId',
      '$.contextCount',
      '$.createdContext'
    )
WHERE operation = 'FAVORITE_SAVE';
