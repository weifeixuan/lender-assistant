package com.lender.assistant.testutil;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * 集成测试基类：真实 PostgreSQL（pgvector 官方镜像）+ 仓库现有 docs/schema.sql。
 *
 * <p>两点刻意设计：</p>
 * <ul>
 *   <li>schema 不复制到 test resources，直接挂载 docs/schema.sql——表结构只有这一份出处，
 *       避免测试库与文档漂移；</li>
 *   <li>容器用「单例 + Ryuk 回收」而不是每个测试类 {@code @Container}：多个测试类共享同一实例，
 *       不会出现「停掉再启动同一容器实例」的复用异常。</li>
 * </ul>
 *
 * <p>无 Docker 的机器上由 {@code @Testcontainers(disabledWithoutDocker = true)} 整体跳过，
 * 配合 surefire 默认 excludedGroups=docker，{@code mvn test} 不会因环境缺 Docker 而变红。</p>
 */
// 集成用例只验证 Mapper 与真实库，不调模型：给一个占位 key 让启动校验通过即可
@SpringBootTest(properties = "spring.ai.openai.api-key=integration-test-key")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresContainerSupport {

    /** 与 compose / 文档一致：pgvector 官方 pg16 镜像 */
    private static final String IMAGE = "pgvector/pgvector:pg16";

    /** 容器初始化脚本落点：镜像 entrypoint 会执行 initdb.d 下的 *.sql */
    private static final String INIT_SCRIPT = "/docker-entrypoint-initdb.d/01_schema.sql";

    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = postgres();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("lender_assistant")
                    .withUsername("postgres")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("docs", "schema.sql").toAbsolutePath()),
                            INIT_SCRIPT);
            postgres.start();
        }
        return postgres;
    }
}
