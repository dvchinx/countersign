package io.github.dvchinx.countersign.autoconfigure.app;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/** Stands in for an application's {@code @SpringBootApplication} class: its package is the application's package. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class BankApplication {}
