# Flight SQL JDBC driver vs Dremio OSS — timestamp tests

Standalone Maven project (not part of the arrow-java reactor) that runs the shaded
`flight-sql-jdbc-driver` against a live Dremio OSS server, focused on TIMESTAMP/DATE/TIME handling.

## Run

```sh
# 1. Dremio OSS
docker run -d --name dremio -p 9047:9047 -p 32010:32010 dremio/dremio-oss:latest
curl -X PUT http://localhost:9047/apiv2/bootstrap/firstuser -H 'Content-Type: application/json' \
  -d '{"userName":"dremio","firstName":"d","lastName":"d","email":"d@d.com","createdAt":1700000000000,"password":"dremio123"}'

# 2. Build and install the driver (from the repo root)
mvn -DskipTests -Dmdep.analyze.skip=true -pl bom,flight/flight-sql-jdbc-driver -am install

# 3. Tests (override with -Ddremio.url / -Ddremio.user / -Ddremio.password)
cd dev/dremio-jdbc-it && mvn test
```

Classes are skipped when Dremio is unreachable. Most tests are repeated with the JVM default
zone set to UTC, Europe/Madrid, America/New_York, Asia/Kolkata, Pacific/Auckland, America/Sao_Paulo.

## Findings so far (Dremio 26.0.5, driver 20.0.0-SNAPSHOT)

Driver:
- With a non-UTC JVM zone, `getTimestamp/getObject/getDate/getTime` on a TIMESTAMP are shifted by the
  zone offset; `getString` and `getObject(i, LocalDateTime.class)` are correct.
  Connecting with `timeZone=UTC` works around it for `getTimestamp`.
- TIME columns are shifted by the JVM zone offset (`getTime`, `getString`).
- `getTimestamp(i, Calendar)` evaluates the offset at the wrong instant around DST changes / LMT.
- `getColumnClassName` is null; `getColumns` reports DATA_TYPE 2014 and COLUMN_SIZE 3 for TIMESTAMP.
- `setObject(i, LocalDateTime)` is unsupported.

Dremio:
- `DATE_TRUNC` to SECOND/MINUTE/HOUR/DAY on table columns rounds pre-1970 values up.
- Millisecond precision only; sub-ms literals are truncated.

Known test-harness issue: `TimestampPreparedStatementTest` mostly fails even in UTC while a manual
probe binds correctly, so those failures are not yet attributed to the driver.
