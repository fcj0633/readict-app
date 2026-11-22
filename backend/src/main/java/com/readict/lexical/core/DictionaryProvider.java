package com.readict.lexical.core;

import tools.jackson.databind.JsonNode;

public interface DictionaryProvider {
    record DatasetRef(String datasetId,String datasetVersion,String normalizationVersion,String provider,String policyVersion) {}
    record LookupResult(String status,String queryKey,DatasetRef dataset,String resolvedLemmaKey,JsonNode regions) {}
    LookupResult lookup(String query,String posHint);
}
