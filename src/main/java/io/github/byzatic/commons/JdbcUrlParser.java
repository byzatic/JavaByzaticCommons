package io.github.byzatic.commons;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdbcUrlParser {
    private final static Logger logger = LoggerFactory.getLogger(JdbcUrlParser.class);
    private final String host;
    private final String port;
    private final String databaseName;
    private final String jdbcPart;
    private final String dbType;

    public JdbcUrlParser(String jdbcUrl) {
        // Регулярное выражение для извлечения jdbc, postgresql, host, port и databaseName
        String regex = "^(jdbc):([\\w]+)://([\\w\\.]+):(\\d+)/(\\w+)$";

        // Компилируем регулярное выражение
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(jdbcUrl);

        // Проверяем, соответствует ли строка шаблону
        if (matcher.matches()) {
            jdbcPart = matcher.group(1);     // "jdbc"
            dbType = matcher.group(2);       // "postgresql"
            host = matcher.group(3);         // host
            port = matcher.group(4);         // port
            databaseName = matcher.group(5); // database name

            logger.debug("JDBC: " + jdbcPart);
            logger.debug("DB Type: " + dbType);
            logger.debug("Host: " + host);
            logger.debug("Port: " + port);
            logger.debug("Database Name: " + databaseName);
        } else {
            String errMessage = "Invalid JDBC URL format";
            logger.error(errMessage);
            throw new RuntimeException(errMessage);
        }
    }

    public String getHost() {
        return host;
    }

    public String getPort() {
        return port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getJdbcPart() {
        return jdbcPart;
    }

    public String getDbType() {
        return dbType;
    }
}
