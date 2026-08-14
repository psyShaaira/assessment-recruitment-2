# Project Structure

```
assessment-recruitment-2/
├── recruitment-be/                  # Spring Boot REST API
│   ├── src/main/java/com/psybergate/recruitment/
│   │   ├── ai/            # Groq LLM integration (AiService, GroqClient) — optional
│   │   ├── assessment/     # Assessment CRUD, question assembly, randomisation, preview
│   │   ├── auth/           # Staff login (JWT) + candidate token issuance
│   │   ├── candidate/      # Candidate CRUD, blacklist, history
│   │   ├── common/         # GlobalExceptionHandler (@RestControllerAdvice)
│   │   ├── config/         # DevDataSeeder + app-level Spring config beans
│   │   ├── dashboard/      # Dashboard statistics (pipeline stats, activity feed)
│   │   ├── domain/         # Shared JPA entities (used by 2+ features)
│   │   ├── email/          # Email templates and sending
│   │   ├── execution/      # Code execution via Piston (CodeExecutionService, PistonClient)
│   │   ├── flag/           # Submission flagging + audit trail (own domain/, repository/)
│   │   ├── invitation/     # Candidate invitations (password-protected)
│   │   ├── marking/        # Manual scoring of submissions (SubmissionController)
│   │   ├── question/       # Question bank management (own domain/TextQuestion)
│   │   ├── reminder/       # Scheduled reminder emails + send-history log
│   │   ├── repository/     # Shared repositories (used by 2+ features)
│   │   ├── security/       # JwtAuthenticationFilter, JwtService, SecurityConfig
│   │   ├── staff/          # Staff account management
│   │   ├── tag/            # Question tagging (own repository/)
│   │   └── take/           # Candidate assessment-taking flow (session, answers, submit)
│   ├── src/main/resources/
│   │   ├── db/migration/   # Flyway migrations (V1–V22, sequential, no ddl-auto)
│   │   ├── db/seed/        # Dev-only seed data (loaded only in `dev` profile)
│   │   └── application*.yaml  # application.yaml + application-{dev,staging,prod}.yaml
│   └── src/test/java/      # Mirrors main package structure; *ServiceTest (unit), *ControllerIntegrationTest (integration)
├── recruitment-fe/                  # Angular SPA
│   ├── src/app/
│   │   ├── core/            # Per-domain services/models: assessment, auth, candidate,
│   │   │                    #   dashboard, execution, flag, marking, question, reminder,
│   │   │                    #   staff, take, theme, toast
│   │   ├── features/        # Page-level lazy components: assessments, candidates,
│   │   │                    #   completed-assessments, dashboard, flags, login,
│   │   │                    #   questions, results, staff
│   │   ├── shared/           # Reusable components: code-editor, code-runner (Monaco-based)
│   │   ├── guards/           # auth.guard.ts, candidate.guard.ts
│   │   ├── layout/           # shell.component.ts (single app shell)
│   │   ├── app.routes.ts     # Route configuration
│   │   └── app.config.ts     # App-level providers (router, http client + interceptor)
│   └── public/                # Static assets
├── db/init.sql               # Spring Session JDBC schema
├── docker-compose.yml         # Local Docker stack (db, mailhog, piston, backend, frontend)
├── openspec/                  # OpenSpec workflow — see below
├── ux-design/                 # Early React-style prototype artifacts (not the real app)
└── .kiro/                     # Kiro steering + specs
```

## Backend Conventions

### Package structure
- **Package-by-feature**: each feature package owns its `XxxController`, `XxxService` interface + `XxxServiceImpl`, and `dto/` subpackage. A feature may also have its own `domain/` and `repository/` subpackages for entities/repos it exclusively owns (e.g. `flag/domain/`, `question/domain/TextQuestion`, `tag/repository/`).
- Entities and repositories shared across 2+ features live in the **top-level** `domain/` and `repository/` packages — do not duplicate them into a feature package.

### Dependency injection
- Always inject the **service interface** (e.g. `AssessmentService`), never the `*Impl`, from controllers and other services.
- **Constructor injection via Lombok `@RequiredArgsConstructor`** with `private final` fields. No field-level `@Autowired`.

### Database
- Schema is managed exclusively by **Flyway** migrations in `src/main/resources/db/migration/` (currently V1–V22). Never rely on Hibernate `ddl-auto` to create/alter schema in non-dev profiles — `ddl-auto` is `none` at the base level (the `dev` profile overrides to `update` for convenience only).
- Dev-only seed data goes in `db/seed/`, loaded only when the `dev` profile's Flyway locations include it — never ship seed data as a real migration.

### Exception handling
- Unmapped exceptions are caught by `common/GlobalExceptionHandler` (`@RestControllerAdvice`), returning `ProblemDetail` responses (`AccessDeniedException` → 403, `DataIntegrityViolationException` → 409, generic → 500 unless a `@ResponseStatus`/`ErrorResponse` type says otherwise).
- Throw `ResponseStatusException` or a custom `@ResponseStatus`-annotated exception class for new failure modes (e.g. `DuplicateInviteException`, `AssessmentAlreadyCompletedException` in `invitation/`) — do not add ad hoc `try/catch` blocks.

### Configuration
- All externalized config (DB, JWT, mail, Piston, Groq) lives in `application.yaml` plus profile-specific `application-{dev,staging,prod}.yaml`, driven by environment variables with dev-only local defaults.

### Security
- `/api/auth/**` and `/api/candidate/**` are public; `/api/take/**` requires `ROLE_CANDIDATE`; `/api/submissions/**` requires `ROLE_RECRUITER` or `ROLE_ADMIN`; everything else requires authentication. Stateless sessions, CSRF disabled (pure JWT bearer auth), `JwtAuthenticationFilter` runs before `UsernamePasswordAuthenticationFilter`.

### Code execution
- `CODE_SUBMISSION` questions compile and run candidate Java through the self-hosted **Piston** engine at `PISTON_BASE_URL` (default `http://localhost:2000/api/v2` locally, `http://piston:2000/api/v2` in Docker). The public emkc.org API is whitelist-only and unusable.

## Frontend Conventions

### Component style
- **Standalone components only** — no NgModules.
- All routes are **lazy-loaded** via `loadComponent()` in `app.routes.ts`; the only eagerly-loaded route is `/login`.
- App-level providers are registered in `app.config.ts` (router, `provideHttpClient(withInterceptors([authInterceptor]))`).

### Services
- Domain services live under `core/{domain}/` and are the single source of truth for API calls within that domain. Components call services; services call `HttpClient`.

### TypeScript
- Strict mode is enabled — no `any`, no implicit overrides, strict templates and injection parameters. Run `npx tsc --noEmit` to type-check without a build.

### Testing
- Tests use **Vitest** via the Angular CLI's native `@angular/build:unit-test` builder — never Karma/Jasmine.
- Format code with **Prettier** before committing.

## Key API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/auth/login` | Staff login — returns JWT |
| POST | `/api/auth/candidate-token` | Candidate access token |
| `*` | `/api/candidate/**` | Candidate-facing routes (public) |
| `*` | `/api/take/**` | Assessment-taking routes (`ROLE_CANDIDATE`) |
| `*` | `/api/submissions/**` | Staff-only submission routes (`ROLE_RECRUITER`/`ROLE_ADMIN`) |

SpringDoc OpenAPI UI is at `/swagger-ui.html` when the backend is running.

## Spec Workflows in This Repo

Two spec systems coexist — check both before starting new work:
- **OpenSpec** (`openspec/`): `openspec/specs/` holds ~75 current capability specs (one `spec.md` per capability, e.g. `assessment-crud`, `mcq-auto-marking`); `openspec/changes/archive/` holds every past change proposal (`.openspec.yaml`, `proposal.md`, `design.md`, `tasks.md`, plus a `specs/<capability>/` diff per capability touched). No active (non-archived) changes currently exist. Slash commands: `.claude/commands/opsx/{new,propose,explore,apply,archive,ff}.md`.
- **Kiro specs** (`.kiro/specs/{feature-name}/`): `requirements.md`, `design.md`, `tasks.md` — used for feature work initiated through Kiro's own spec workflow.

## Feature Development Workflow

1. Check `openspec/specs/` for an existing capability spec before creating a new one; use OpenSpec (`/opsx:new`) for changes that fit the established capability model, or a Kiro spec for other feature work.
2. Write tests (unit + integration) alongside the implementation.
3. Add a Flyway migration for any schema change — never rely on `ddl-auto`.
4. Keep backend and frontend changes in separate commits when practical.
5. Ensure `./mvnw test` and `npm test` pass, and mutation coverage stays above the PIT threshold (29), before opening a PR.
6. CI runs on every push/PR to `main` — it must stay green.
