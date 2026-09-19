package com.example.domain.zsxq.ingest;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 落库用的数据源装配（zsxq 管道专用）。
 *
 * <p>为什么直接用 JdbcTemplate 而不是项目里已有的 MyBatis-Plus：
 * <ul>
 *   <li>这是一条<b>离线批处理</b>管道（CLI 驱动），不走 Web 容器，起 MyBatis 的 SqlSessionFactory 太重；</li>
 *   <li>后面写向量列要用 {@code ?::vector} 做类型转换、用 {@code ON CONFLICT} 做幂等 upsert，
 *       都是手写 SQL 更直接，走 ORM 反而要额外写 TypeHandler。</li>
 * </ul>
 * 在线业务侧（chat_message 等）仍然用 MyBatis-Plus，两边不冲突。
 *
 * <p>连接参数从 Environment 读（系统属性 / 环境变量 / 命令行 -D 都行），默认值跟
 * {@code application.yml} 的 spring.datasource 保持一致，避免两套配置各写一份。
 */
@Configuration
public class ZsxqIngestConfig {

    @Bean
    public DataSource zsxqDataSource(Environment env) {
        String host = env.getProperty("PG_HOST", "localhost");
        String port = env.getProperty("PG_PORT", "5432");
        String db = env.getProperty("PG_DATABASE", "jlra_demo");
        String user = env.getProperty("PG_USER", "postgres");
        String password = env.getProperty("PG_PASSWORD", "postgres");

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    public JdbcTemplate zsxqJdbcTemplate(DataSource zsxqDataSource) {
        return new JdbcTemplate(zsxqDataSource);
    }
}
