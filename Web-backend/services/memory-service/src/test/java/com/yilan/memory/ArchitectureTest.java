package com.yilan.memory;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    @Test
    void domainDoesNotDependOnFrameworkOrDataImplementations() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "io.grpc..",
                        "java.sql..",
                        "javax.sql..",
                        "jakarta.persistence..",
                        "org.springframework.jdbc..",
                        "org.springframework.data.redis..",
                        "redis.clients..",
                        "org.neo4j..",
                        "org.springframework.data.neo4j..")
                .allowEmptyShould(true)
                .check(importedProductionClasses());
    }

    @Test
    void domainAndApplicationDoNotDependDirectlyOnAsyncInfrastructureOrWorkerWireTypes() {
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data.redis..",
                        "redis.clients..",
                        "org.neo4j..",
                        "org.springframework.data.neo4j..",
                        "io.grpc..",
                        "com.yilan.memory.contract.v1..")
                .allowEmptyShould(true)
                .check(importedProductionClasses());
    }

    @Test
    void workerAdapterDoesNotDependOnDataAdapters() {
        noClasses().that().resideInAPackage("..adapter.worker..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.postgres..",
                        "..adapter.redis..",
                        "..adapter.cache..",
                        "..adapter.neo4j..")
                .allowEmptyShould(true)
                .check(importedProductionClasses());
    }

    @Test
    void cacheAndNeo4jAdaptersDoNotDependOnAuthorityWriterAdapters() {
        noClasses().that().resideInAnyPackage("..adapter.cache..", "..adapter.neo4j..")
                .should().dependOnClassesThat().haveNameMatching(
                        "com\\.yilan\\.memory\\.adapter\\.postgres\\.(JdbcCandidateRepository|"
                                + "JdbcInteractionEventRepository|JdbcMemoryHistoryRepository|"
                                + "JdbcTransactionalOutboxRepository)")
                .allowEmptyShould(true)
                .check(importedProductionClasses());
    }

    @Test
    void nonAuthorityExternalAdaptersDoNotHandlePayloadOrKeyMaterial() {
        noClasses().that().resideInAnyPackage(
                        "..adapter.rest..", "..adapter.grpc..", "..adapter.redis..", "..adapter.worker..")
                .should().dependOnClassesThat().haveNameMatching(
                        "com\\.yilan\\.memory\\.application\\.privacy\\.(DataKeyProvider|PayloadProtector|EncryptedPayload)")
                .allowEmptyShould(true)
                .check(importedProductionClasses());
    }

    private static com.tngtech.archunit.core.domain.JavaClasses importedProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.yilan.memory");
    }
}
