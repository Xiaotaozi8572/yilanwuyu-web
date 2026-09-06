package com.yilan.memory.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class Neo4jIntegrationTest extends PostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    protected static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26.0"))
            .withAdminPassword("memory-test-password");

    protected Driver driver;

    @BeforeEach
    void openNeo4jDriver() {
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", "memory-test-password"));
        driver.verifyConnectivity();
    }

    @AfterEach
    void closeNeo4jDriver() {
        if (driver != null) {
            driver.close();
        }
    }
}
