package com.readict.lexical.tool;

import com.readict.lexical.core.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Explicit imports only: intentionally not a component-scanned configuration. */
@ImportAutoConfiguration({
 org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
 org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
 org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration.class,
 org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class,
 org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration.class,
 com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class
})
@MapperScan("com.readict.lexical.infrastructure.persistence.mapper")
@Import({DictionaryImporter.class,LocalDictionaryProvider.class})
public class DictionaryToolConfiguration {}
