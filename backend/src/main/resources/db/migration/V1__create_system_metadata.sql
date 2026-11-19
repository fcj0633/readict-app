CREATE TABLE system_metadata (
  metadata_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  metadata_value VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (metadata_key),
  CONSTRAINT ck_system_metadata_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO system_metadata(metadata_key,metadata_value,version,updated_at)
VALUES ('application','readict',0,UTC_TIMESTAMP(3));
