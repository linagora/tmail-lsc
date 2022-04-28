package org.lsc.plugins.connectors.james.config;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class SyncContactConfig {
    public static final String DELIMITER = ",";
    public static final Optional<List<String>> DOMAIN_LIST_TO_SYNCHRONIZE = Optional.ofNullable(System.getenv("DOMAIN_LIST_TO_SYNCHRONIZE"))
        .map(envList -> Arrays.asList(envList.split(DELIMITER)));
}
