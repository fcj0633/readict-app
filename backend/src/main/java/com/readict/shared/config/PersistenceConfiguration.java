package com.readict.shared.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.readict.*.infrastructure.persistence.mapper")
public class PersistenceConfiguration {}
