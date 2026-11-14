# commercecore-backend

## Configuración de CommerceCore

El proyecto usa Java 21 y Quarkus 3. Incluye REST con Jackson, Panache,
validación, el controlador JDBC de PostgreSQL, Flyway, OpenAPI, Health y OIDC.
Las pruebas disponen de Quarkus Test, REST Assured y Quarkus Test Security.

Para ejecutar en producción, proporciona las siguientes variables de entorno
mediante el entorno de despliegue, sin guardar contraseñas en el repositorio:

| Variable | Valor |
| --- | --- |
| `DB_JDBC_URL` | URL JDBC de PostgreSQL, por ejemplo `jdbc:postgresql://servidor:5432/commercecore?sslmode=verify-full` |
| `DB_USERNAME` | Usuario de la base de datos |
| `DB_PASSWORD` | Contraseña de la base de datos |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | URL del proveedor OIDC |
| `QUARKUS_OIDC_CLIENT_ID` | Identificador del cliente OIDC, cuando lo requiera el proveedor |
| `QUARKUS_OIDC_CREDENTIALS_SECRET` | Secreto del cliente, cuando lo requiera el proveedor |

OIDC está incluido, pero los endpoints existentes no tienen reglas de acceso;
estas deben definirse junto con los usuarios y roles de la aplicación. En
desarrollo y pruebas se pueden usar los Dev Services de Quarkus si Docker está
disponible, o configurar servicios externos mediante las mismas variables.

Para conexiones remotas, configura el certificado raíz confiable del servidor
en el entorno PostgreSQL JDBC y usa verificación TLS apropiada.

Flyway ejecuta las migraciones al iniciar en desarrollo y pruebas. En producción
valida las migraciones al iniciar sin ejecutarlas; aplica las migraciones como
paso separado del despliegue. Flyway tiene deshabilitada la operación `clean` y
Hibernate no modifica el esquema.
Las migraciones SQL versionadas deben ubicarse en
`src/main/resources/db/migration`, por ejemplo `V1__crear_tablas.sql`. Todavía
no hay migraciones ni tablas de negocio: se crearán al definir el modelo. La
entidad y el endpoint de ejemplo generados por Quarkus no son un módulo de negocio.

OpenAPI está disponible en `/q/openapi`; Swagger UI está disponible por defecto
en desarrollo y pruebas en `/q/swagger-ui`. Los checks están en `/q/health`,
`/q/health/live` y `/q/health/ready`.

Ejecuta `mvnw.cmd test` en Windows o `./mvnw test` en Unix. El módulo Media
incorporará AWS SDK for Java v2 (`software.amazon.awssdk:s3`, que incluye el
presigner) y la importación Excel incorporará `org.apache.poi:poi-ooxml` cuando
se implementen esas funcionalidades.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/commercecore-backend-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- Hibernate Validator ([guide](https://quarkus.io/guides/validation)): Bean validation using Hibernate Validator and Jakarta Validation annotations
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to PostgreSQL via JDBC
- SmallRye OpenAPI ([guide](https://quarkus.io/guides/openapi-swaggerui)): Generate OpenAPI schemas and serve Swagger UI for REST API documentation
- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- OpenID Connect ([guide](https://quarkus.io/guides/security-openid-connect)): Secure applications with OpenID Connect and OAuth 2.0 using bearer tokens and authorization code flow
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplified JPA/Hibernate data access layer with active record and repository patterns
- SmallRye Health ([guide](https://quarkus.io/guides/smallrye-health)): Monitor service health

## Provided Code

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)


[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)


### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)

### SmallRye Health

Monitor your application's health using SmallRye Health

[Related guide section...](https://quarkus.io/guides/smallrye-health)
