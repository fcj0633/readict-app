CREATE TABLE dictionary_audio_dataset (
 id BINARY(16) NOT NULL PRIMARY KEY,
 version VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
 identity_sha BINARY(32) NOT NULL,
 status VARCHAR(16) NOT NULL,
 manifest_sha BINARY(32) NOT NULL,
 manifest_json JSON NOT NULL,
 expected_assets BIGINT NOT NULL,
 expected_mappings BIGINT NOT NULL,
 imported_assets BIGINT NOT NULL DEFAULT 0,
 imported_mappings BIGINT NOT NULL DEFAULT 0,
 last_asset_ordinal BIGINT NOT NULL DEFAULT 0,
 last_mapping_ordinal BIGINT NOT NULL DEFAULT 0,
 lease_owner BINARY(16) NULL,
 lease_until DATETIME(6) NULL,
 error_code VARCHAR(100) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_audio_identity(identity_sha), UNIQUE KEY uq_audio_version(version),
 KEY ix_audio_status(status,updated_at),
 CHECK(status IN ('IMPORTING','READY','FAILED')),
 CHECK(expected_assets>0 AND expected_mappings>=0 AND imported_assets>=0 AND imported_mappings>=0 AND last_asset_ordinal>=0 AND last_mapping_ordinal>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE dictionary_audio_asset (
 id BINARY(16) NOT NULL PRIMARY KEY,
 dataset_id BINARY(16) NOT NULL,
 source_ordinal BIGINT NOT NULL,
 source_key VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL,
 source_sha BINARY(32) NOT NULL,
 output_sha BINARY(32) NOT NULL,
 storage_key VARCHAR(160) COLLATE utf8mb4_0900_bin NOT NULL,
 bytes BIGINT NOT NULL,
 duration_ms INT NOT NULL,
 sample_rate INT NOT NULL,
 channels TINYINT NOT NULL,
 mime VARCHAR(32) NOT NULL,
 UNIQUE KEY uq_audio_asset_scope(dataset_id,id),
 UNIQUE KEY uq_audio_source(dataset_id,source_ordinal),
 KEY ix_audio_output(dataset_id,output_sha),
 CONSTRAINT fk_audio_asset_dataset FOREIGN KEY(dataset_id) REFERENCES dictionary_audio_dataset(id),
 CHECK(source_ordinal>=0 AND bytes>0 AND bytes<=2097152 AND duration_ms>0 AND duration_ms<=60080),
 CHECK(sample_rate=22050 AND channels=1 AND mime='audio/mpeg')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE dictionary_audio_mapping (
 dataset_id BINARY(16) NOT NULL,
 source_ordinal BIGINT NOT NULL,
 link_order INT NOT NULL,
 index_key VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
 display_headword VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
 word_key VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
 accent VARCHAR(8) NOT NULL,
 audio_id BINARY(16) NOT NULL,
 evidence_json JSON NOT NULL,
 PRIMARY KEY(dataset_id,source_ordinal,link_order),
 KEY ix_audio_word(dataset_id,word_key,accent),
 CONSTRAINT fk_audio_mapping_asset FOREIGN KEY(dataset_id,audio_id) REFERENCES dictionary_audio_asset(dataset_id,id),
 CHECK(source_ordinal>=0 AND link_order>=0 AND accent IN ('en-GB','en-US'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE dictionary_audio_active (
 slot TINYINT NOT NULL PRIMARY KEY,
 dataset_id BINARY(16) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_audio_active_dataset FOREIGN KEY(dataset_id) REFERENCES dictionary_audio_dataset(id),
 CHECK(slot=1 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO dictionary_audio_active(slot,dataset_id,version) VALUES(1,NULL,0);
