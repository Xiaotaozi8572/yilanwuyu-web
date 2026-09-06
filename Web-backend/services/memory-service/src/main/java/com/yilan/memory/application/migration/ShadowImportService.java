package com.yilan.memory.application.migration;

import com.yilan.memory.adapter.postgres.JdbcShadowImportRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Migration-only command facade. It stages encrypted records in PostgreSQL
 * and exposes no context-resolution or authority-write path.
 */
public final class ShadowImportService {

    private static final int AES_256_KEY_BYTES = 32;

    private final LegacyRecordMapper mapper;
    private final JdbcShadowImportRepository repository;
    private final byte[] migrationKey;

    public ShadowImportService(JdbcShadowImportRepository repository, byte[] migrationKey) {
        this(new LegacyRecordMapper(), repository, migrationKey);
    }

    ShadowImportService(LegacyRecordMapper mapper, JdbcShadowImportRepository repository, byte[] migrationKey) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.repository = Objects.requireNonNull(repository, "repository");
        if (migrationKey == null || migrationKey.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("migration key must be exactly 32 bytes");
        }
        this.migrationKey = migrationKey.clone();
    }

    public ImportReconciliationReport importShadow(MigrationBundleReader.MigrationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        // Complete validation happens before the transaction can stage a row.
        var validation = mapper.validate(bundle);
        return repository.stage(bundle, validation, migrationKey);
    }

    /** SHADOW records are intentionally never exposed to answer-context resolution. */
    public List<MigrationBundleReader.CanonicalRecord> resolveForContext(String pseudonymousSubject) {
        return List.of();
    }

    public long shadowRecordCount() {
        return repository.shadowRecordCount();
    }

    public static ImportCommand parseCommand(List<String> arguments) {
        if (arguments == null || arguments.size() != 7 || !"memory-import".equals(arguments.get(0))
                || !"--bundle".equals(arguments.get(1)) || !"--key-env".equals(arguments.get(3))
                || !"--mode".equals(arguments.get(5)) || !"shadow".equals(arguments.get(6))
                || arguments.get(2).isBlank() || arguments.get(4).isBlank()) {
            throw new IllegalArgumentException("expected: memory-import --bundle <path> --key-env <name> --mode shadow");
        }
        return new ImportCommand(Path.of(arguments.get(2)), arguments.get(4), "shadow");
    }

    /**
     * Direct command entry only: it never starts Spring's web listener. The
     * PostgreSQL target is supplied for this one-shot migration by local env.
     */
    public static void main(String[] arguments) {
        var command = parseCommand(List.of(arguments));
        var environment = System.getenv();
        var key = MigrationBundleReader.keyFromEnvironment(environment, command.keyEnvironment());
        var bundle = new MigrationBundleReader().read(command.bundle(), key);
        var dataSource = new DriverManagerDataSource(
                requiredEnvironment(environment, "MEMORY_MIGRATION_JDBC_URL"),
                requiredEnvironment(environment, "MEMORY_MIGRATION_JDBC_USERNAME"),
                requiredEnvironment(environment, "MEMORY_MIGRATION_JDBC_PASSWORD"));
        var repository = new JdbcShadowImportRepository(
                JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource));
        var report = new ShadowImportService(repository, key).importShadow(bundle);
        System.out.printf("shadow import: source=%d accepted=%d rejected=%d duplicate=%d newly_imported=%d%n",
                report.sourceTotal(), report.accepted(), report.rejected(), report.duplicate(), report.newlyImported());
    }

    private static String requiredEnvironment(java.util.Map<String, String> environment, String name) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("migration database environment variable is required: " + name);
        }
        return value;
    }

    public record ImportCommand(Path bundle, String keyEnvironment, String mode) {
        public ImportCommand {
            bundle = Objects.requireNonNull(bundle, "bundle");
            if (keyEnvironment == null || keyEnvironment.isBlank() || !"shadow".equals(mode)) {
                throw new IllegalArgumentException("migration import command is invalid");
            }
        }
    }
}
