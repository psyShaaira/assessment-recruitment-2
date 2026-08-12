# Technology Stack

## Backend (`recruitment-be/`)

- **Spring Boot 4.0.6**, Java 17, Maven (wrapper: `mvnw`/`mvnw.cmd`)
- **Spring Security** (`@EnableWebSecurity`, `@EnableMethodSecurity`) + JWT (`io.jsonwebtoken:jjwt-* 0.12.6`), stateless sessions
- **Spring Session JDBC** — session persistence in PostgreSQL (schema in `db/init.sql`)
- **Spring Data JPA** + **Flyway** (`flyway-core`, `flyway-database-postgresql`) for schema migrations
- **Spring Mail** for notifications (invites, reminders, contact-candidate)
- **Spring Web MVC** for REST; **Spring Web Services** for SOAP endpoints
- **PostgreSQL** (`postgresql:16-alpine` in Docker) as the primary database
- **Piston** (self-hosted, `ghcr.io/engineer-man/piston`) for sandboxed Java code execution — the public emkc.org API is whitelist-only and cannot be used
- **Groq** (OpenAI-compatible API) for optional AI-assisted features (`ai/` package) — safe to omit `GROQ_API_KEY`; only AI calls fail, not startup
- **SpringDoc OpenAPI** (`springdoc-openapi-starter-webmvc-ui:2.8.9`) — Swagger UI at `/swagger-ui.html`
- **Lombok** for boilerplate (constructor injection via `@RequiredArgsConstructor`)
- **TestContainers** (`testcontainers-bom:1.20.6`) for integration tests against real PostgreSQL
- **JaCoCo** (`0.8.13`) for coverage, **PIT** (`pitest-maven:1.17.0` + `pitest-junit5-plugin:1.2.1`) for mutation testing
- **jqwik** (`1.9.3`, test scope) — available for property-based testing

### Backend commands
```powershell
cd recruitment-be
./mvnw spring-boot:run                                          # run (dev profile)
./mvnw clean package                                            # build
./mvnw test                                                      # all tests
./mvnw test -Dtest=MyTestClass                                   # single class
./mvnw test -Dtest=MyTestClass#myMethod                          # single method
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage     # mutation testing
```

- Integration tests require Docker Desktop (TestContainers spins up `postgres:16-alpine`). On Docker Engine 29+, if tests fail with "Could not find a valid Docker environment", add `api.version=1.44` to `%USERPROFILE%\.docker-java.properties`.
- PIT config (`pom.xml`): targets `com.psybergate.recruitment.*`, test pattern `*Test`, excludes `*.dto.*` classes and `*IntegrationTest`/`RecruitmentApplicationTests`, **mutation threshold 29**, reports in `target/pit-reports` (HTML + XML).
- On Windows, a `windows-nio-pipe-fix` Maven profile auto-activates and sets `-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp` (works around a JDK 24+ Tomcat NIO startup bug).

## Frontend (`recruitment-fe/`)

- **Angular 21.2** (`^21.2.x` for core/cdk/router/forms/etc.) with standalone components — no NgModules
- **Angular Router** — lazy-loaded routes via `loadComponent()`, functional guards
- **Angular HTTP client** — `provideHttpClient(withInterceptors([authInterceptor]))`, single functional auth interceptor
- **Monaco Editor** (`^0.55.1`) — copied as a static asset to `/monaco` (not bundled), used for the in-browser Java code editor
- **RxJS** `~7.8.0`, **TypeScript** `~5.9.2` (strict mode: `noImplicitOverride`, `strictTemplates`, `strictInjectionParameters`)
- **Vitest** (`^4.0.8`, `@vitest/coverage-v8`, `jsdom`) via the Angular CLI's native unit-test builder (`@angular/build:unit-test`) — not Karma/Jasmine
- **Prettier** (`^3.8.1`) for formatting
- Package manager: **npm** `11.11.0`, Node 22

### Frontend commands
```powershell
cd recruitment-fe
npm install
npm start           # dev server at http://localhost:4200 (proxy.conf.json)
npm run build       # production build
npm test            # Vitest
npx tsc --noEmit    # type-check only
```

## Infrastructure

```powershell
docker compose up --build          # full stack
docker compose up db mailhog piston  # infra only, for local dev
docker compose up piston-init        # one-time: install Java runtime into Piston (after piston is healthy)
```

| Service | Host URL/Port | Notes |
|---------|----------------|-------|
| `frontend` | http://127.0.0.1:4200 | Angular via nginx, loopback-only |
| `backend` | http://localhost:8080 | Spring Boot API |
| `db` | localhost:5433 → container 5432 | avoids clashing with a natively-installed Postgres on 5432 |
| `mailhog` | SMTP 1025, UI http://localhost:8025 | captured emails |
| `piston` | http://localhost:2000 | sandboxed Java runtime, `privileged: true` |
| `piston-init` | (one-shot) | installs Java 15.0.2 runtime into the `piston` volume, idempotent |

Key environment variables (see `.env.example`, `application.yaml`):
- `DB_NAME` / `DB_USER` / `DB_PASSWORD` (default `recruitment`/`recruitment`/`recruitment`)
- `SPRING_PROFILES_ACTIVE` (default `dev`)
- `JWT_SECRET`, `JWT_EXPIRY_HOURS` (default 1 hour)
- `APP_BASE_URL` (default `http://localhost:4200`)
- `PISTON_BASE_URL` (default `http://localhost:2000/api/v2` locally, `http://piston:2000/api/v2` in Docker)
- `GROQ_API_KEY`, `GROQ_BASE_URL`, `GROQ_MODEL` (optional AI features; omit safely)

## Testing Conventions

### Backend
- Unit tests: `*ServiceTest`, `@ExtendWith(MockitoExtension.class)`, `@Mock` collaborators + `@InjectMocks` on the `*ServiceImpl`, AssertJ assertions (`assertThat`, `assertThatThrownBy(...).isInstanceOfSatisfying(...)`).
- Integration tests: `*ControllerIntegrationTest` / `*IntegrationTest`, extend a shared `AbstractIntegrationTest`, `@AutoConfigureMockMvc`, `@ContextConfiguration(initializers = TestDatasourceInitializer.class)` which boots a static `PostgreSQLContainer` once via TestContainers. Use `MockMvc` + `ObjectMapper` (note: Spring Boot 4 uses the new `tools.jackson.databind.ObjectMapper`, Jackson 3) with `jsonPath` assertions and real repository/`PasswordEncoder`/`JwtService` beans.
- `dto` packages and `*IntegrationTest`/`RecruitmentApplicationTests` are excluded from mutation testing.
- Property-based tests may use `jqwik` where a correctness property is being validated.

### Frontend
- `*.spec.ts` colocated next to source. Vitest BDD API (`describe`/`it`/`beforeEach`/`afterEach`, `expect(...).toBe(...)`).
- `TestBed.configureTestingModule` with `provideHttpClient()` + `provideHttpClientTesting()` + `provideRouter([])`; `HttpTestingController` for HTTP assertions (`httpMock.expectOne(...).flush(...)`, `httpMock.verify()` in `afterEach`).
- Functional guards tested via `TestBed.runInInjectionContext(...)`.

## CI (`.github/workflows/ci.yml`)

Runs on push/PR to `main`, two parallel jobs, both must stay green:
- **backend**: JDK 17 (Temurin) → `./mvnw clean package -DskipTests -q` → `./mvnw test` → `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage` → uploads `target/pit-reports/` as an artifact.
- **frontend**: Node 22 → `npm ci` → `npx tsc --noEmit` → `npm test`.

No deploy/publish step exists — CI is build + test + type-check + mutation-coverage only.
