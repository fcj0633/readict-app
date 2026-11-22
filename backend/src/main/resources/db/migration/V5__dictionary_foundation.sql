CREATE TABLE dictionary_dataset (
 id BINARY(16) PRIMARY KEY,
 identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 dataset_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 converter_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii NOT NULL,
 import_phase VARCHAR(16) CHARACTER SET ascii NOT NULL,
 checkpoint_line BIGINT NOT NULL DEFAULT 0,
 lease_owner BINARY(16) NULL,
 lease_until DATETIME(3) NULL,
 manifest_json JSON NOT NULL,
 error_code VARCHAR(64) CHARACTER SET ascii NULL,
 created_at DATETIME(3) NOT NULL,
 ready_at DATETIME(3) NULL,
 CONSTRAINT uk_dictionary_release UNIQUE(source_hash,dataset_version,converter_version),
 CONSTRAINT ck_dictionary_status CHECK(status IN ('IMPORTING','READY','FAILED')),
 CONSTRAINT ck_dictionary_phase CHECK(import_phase IN ('ENTRIES','SENSES','FORMS','VERIFY','DONE')),
 CONSTRAINT ck_dictionary_checkpoint CHECK(checkpoint_line>=0),
 CONSTRAINT ck_dictionary_ready CHECK(status<>'READY' OR (ready_at IS NOT NULL AND import_phase='DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE dictionary_active (
 slot TINYINT PRIMARY KEY,
 dataset_id BINARY(16) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_dictionary_slot CHECK(slot=1),
 CONSTRAINT fk_dictionary_active FOREIGN KEY(dataset_id) REFERENCES dictionary_dataset(id)
) ENGINE=InnoDB;
INSERT INTO dictionary_active(slot,dataset_id,version) VALUES(1,NULL,0);
CREATE TABLE dictionary_entry (
 id BINARY(16) NOT NULL,
 dataset_id BINARY(16) NOT NULL,
 entry_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_ordinal BIGINT NOT NULL,
 section_order INT NOT NULL,
 component_order INT NOT NULL,
 headword VARCHAR(128) NOT NULL,
 lemma_key VARCHAR(128) NOT NULL,
 metadata_json JSON NOT NULL,
 PRIMARY KEY(dataset_id,id),
 CONSTRAINT uk_dictionary_entry_key UNIQUE(dataset_id,entry_key),
 KEY ix_dictionary_lemma(dataset_id,lemma_key),
 CONSTRAINT fk_dictionary_entry_dataset FOREIGN KEY(dataset_id) REFERENCES dictionary_dataset(id),
 CONSTRAINT ck_dictionary_entry_order CHECK(source_ordinal>=0 AND section_order>=0 AND component_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE dictionary_sense (
 id BINARY(16) NOT NULL,
 dataset_id BINARY(16) NOT NULL,
 entry_id BINARY(16) NOT NULL,
 provider_order INT NOT NULL,
 pos VARCHAR(32) CHARACTER SET ascii NOT NULL,
 zh TEXT NOT NULL,
 metadata_json JSON NOT NULL,
 PRIMARY KEY(dataset_id,id),
 CONSTRAINT uk_dictionary_sense_order UNIQUE(dataset_id,entry_id,provider_order),
 CONSTRAINT fk_dictionary_sense_entry FOREIGN KEY(dataset_id,entry_id) REFERENCES dictionary_entry(dataset_id,id),
 CONSTRAINT ck_dictionary_sense_order CHECK(provider_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE dictionary_form_mapping (
 dataset_id BINARY(16) NOT NULL,
 surface_key VARCHAR(128) NOT NULL,
 entry_id BINARY(16) NOT NULL,
 relation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 priority INT NOT NULL,
 metadata_json JSON NOT NULL,
 PRIMARY KEY(dataset_id,surface_key,entry_id,relation),
 KEY ix_dictionary_form_entry(dataset_id,entry_id),
 CONSTRAINT fk_dictionary_form_entry FOREIGN KEY(dataset_id,entry_id) REFERENCES dictionary_entry(dataset_id,id),
 CONSTRAINT ck_dictionary_form_relation CHECK(relation IN ('INFLECTION','REDIRECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
