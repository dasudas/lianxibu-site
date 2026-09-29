# Backend bootstrap

This is the Java 21 service skeleton. It starts with all HTTP requests denied and
without scheduling or AI execution. No database operation is performed by the
bootstrap code. Feature modules replace the deny-all policy only when their
authentication and authorization checks are implemented.

Use Maven 3.6.3 or newer with JDK 21:

```sh
mvn test
mvn package
```

Set `SITE_DB_URL`, `SITE_DB_USER`, and `SITE_DB_PASSWORD` through the
host's secret configuration before starting the JAR. The URL must be a MySQL
JDBC URL. Missing configuration prevents startup. The database account
should have only the permissions needed by the application.

`dependency-lock.dot` records the resolved dependency graph for the pinned
Spring Boot 4.1.1 parent. Regenerate and review it whenever the build
dependencies change:

```sh
mvn dependency:tree -DoutputType=dot -DoutputFile=dependency-lock.dot
```
