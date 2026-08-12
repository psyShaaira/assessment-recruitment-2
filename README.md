# Recruitment Platform 

A full-stack recruitment assessment platform for creating, distributing, and evaluating candidate assessments.

It supports multiple question types — multiple-choice, text, and in-browser Java coding challenges — and gives recruiters a dashboard for managing assessments, reviewing submissions, scoring answers, and tracking candidates through the hiring pipeline.

## Table of contents

- [Project overview](#project-overview)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Features](#features)
- [Getting started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Environment variables](#environment-variables)
  - [Run with Docker Compose](#run-with-docker-compose)
  - [Run services locally](#run-services-locally)
- [Backend (`recruitment-be/`)](#backend-recruitment-be)
- [Frontend (`recruitment-fe/`)](#frontend-recruitment-fe)
- [Testing](#testing)
- [CI / CD](#ci--cd)
- [Development notes](#development-notes)
  - [Code execution with Piston](#code-execution-with-piston)
  - [Database migrations](#database-migrations)
  - [Windows JDK 24+ loopback fix](#windows-jdk-24-loopback-fix)
  - [OpenSpec workflow](#openspec-workflow)
- [License](#license)

## Project overview

The repository is split into two sub-projects:

| Sub-project | Technology | Purpose |
|-------------|------------|---------|
| `recruitment-be/` | Spring Boot 4.0.6, Java 17 | REST API, business logic, persistence, email, code execution orchestration |
| `recruitment-fe/` | Angular 21.2, TypeScript | SPA for recruiters and candidates |

## Tech stack

### Backend

- **Spring Boot 4.0.6** on Java 17
- **Spring Security** with JWT authentication
- **Spring Session JDBC** for session persistence in PostgreSQL
- **Spring Data JPA** + **Flyway** migrations
- **Spring Mail** for notifications
- **Spring Web MVC** for REST endpoints
- **Spring Web Services** for SOAP endpoints
- **PostgreSQL** as the primary database
- **Piston** self-hosted engine for sandboxed Java code execution
- **TestContainers** for integration tests
- **PIT** for mutation testing
- **Maven** for builds

### Frontend

- **Angular 21.2** with standalone components (no NgModules)
- **Angular Router** with lazy-loaded routes
- **Angular HTTP client** with an auth interceptor
- **Monaco Editor** for in-browser code editing
- **Vitest** for unit testing
- **Prettier** for code formatting
- TypeScript strict mode enabled

### Infrastructure

- **Docker Compose** for PostgreSQL, MailHog, Piston, and the application containers
- **GitHub Actions** CI for build, test, type-check, and mutation coverage

## Project structure

```
recruitment-project/
├── recruitment-be/                 # Spring Boot REST API
│   ├── src/main/java/com/psybergate/recruitment/
│   │   ├── auth/                   # Login / candidate token generation
│   │   ├── assessment/             # Assessment CRUD + question assembly
│   │   ├── candidate/              # Candidate management
│   │   ├── common/                 # Global exception handling
│   │   ├── dashboard/              # Dashboard statistics
│   │   ├── flag/                   # Submission flagging workflow
│   │   ├── invitation/             # Candidate invitations
│   │   ├── marking/                # Manual scoring of submissions
│   │   ├── question/               # Question bank management
│   │   ├── reminder/               # Scheduled reminder emails
│   │   ├── security/               # JWT filter + security config
│   │   ├── take/                   # Candidate assessment taking flow
│   │   └── domain/                 # Shared JPA entities (feature-owned entities live in each feature's own domain/ subpackage)
│   ├── src/main/resources/
│   │   ├── db/migration/           # Flyway migration scripts
│   │   ├── db/seed/                # Dev seed data
│   │   └── application*.yaml       # Spring profiles
│   └── src/test/java/              # Unit + integration tests
├── recruitment-fe/                 # Angular SPA
│   ├── src/app/
│   │   ├── core/                   # Services, models, auth, interceptors
│   │   ├── features/               # Page-level components
│   │   ├── shared/                 # Reusable components (code editor, runner)
│   │   ├── guards/                 # Route guards
│   │   ├── layout/                 # Shell layout
│   │   ├── app.routes.ts           # Route configuration
│   │   └── app.config.ts           # App-level providers
│   └── src/main.ts
├── db/init.sql                     # Spring Session JDBC schema
├── docker-compose.yml              # Local Docker stack
├── openspec/config.yaml            # OpenSpec AI workflow config
└── ux-design/                      # Early design artifacts
```

## Features

### Recruiter / staff portal

- **Dashboard** with summary statistics and pipeline overview
- **Question bank** supporting:
  - Multiple-choice questions (`MCQ`)
  - Text / essay questions (`TEXT`)
  - Java coding challenges (`CODE_SUBMISSION`)
- **Assessment builder** for composing assessments from questions
- **Assessment preview** before publishing
- **Candidate invitations** with password-protected access
- **Submission review & manual marking**
- **Flagged submissions** workflow for dispute / audit tracking
- **Reminder emails** for pending invitations
- **Staff management**
- **Results listing** for completed assessments

### Candidate experience

- Password-protected assessment entry
- Take assessments with question randomization and snapshots
- Save progress and submit answers
- In-browser Java code editor with real-time execution via Monaco + Piston
- View results after marking

## Getting started

### Prerequisites

- Java 17
- Maven (or use the included wrapper `recruitment-be/mvnw`)
- Node.js 22 + npm
- Docker Desktop (for PostgreSQL, MailHog, Piston, and integration tests)
- Git

### Environment variables

Copy the example environment file and adjust values if needed:

```powershell
cp .env.example .env
```

The defaults in `.env.example` are already configured to match the Docker Compose services:

```env
DB_NAME=recruitment
DB_USER=recruitment
DB_PASSWORD=recruitment
SPRING_PROFILES_ACTIVE=dev
```

To enable AI features, add your Groq API key to `.env`:

```env
GROQ_API_KEY=your-key-here
```

Get a free key at https://console.groq.com. The app starts and runs normally without it — only AI-backed features will return an error at call time.

### Run with Docker Compose

The simplest way to run everything together:

```powershell
docker compose up --build
```

This starts:

| Service | URL / Port | Purpose |
|---------|------------|---------|
| Frontend | http://localhost:4200 | Angular app served via nginx |
| Backend API | http://localhost:8080 | Spring Boot API |
| PostgreSQL | localhost:5433 | Application + session database |
| MailHog SMTP | localhost:1025 | Email capture for testing |
| MailHog UI | http://localhost:8025 | View captured emails |
| Piston | http://localhost:2000 | Sandboxed Java runtime |

### Run services locally

If you prefer to run the backend and frontend directly for faster local iteration:

1. Start the infrastructure:

   ```powershell
   docker compose up db mailhog piston
   ```

2. Wait for Piston to be healthy, then install the Java runtime:

   ```powershell
   docker compose up piston-init
   ```

3. Run the backend:

   ```powershell
   cd recruitment-be
   ./mvnw spring-boot:run
   ```

   The `dev` profile expects PostgreSQL on `localhost:5433` (mapped from the Docker Compose `db` service).

4. In a new terminal, run the frontend:

   ```powershell
   cd recruitment-fe
   npm install
   npm start
   ```

   The dev server is available at http://localhost:4200.

## Backend (`recruitment-be/`)

### Commands

```powershell
# Run the application (dev profile defaults to localhost:5433)
./mvnw spring-boot:run

# Build
./mvnw clean package

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=MyTestClass

# Run a single test method
./mvnw test -Dtest=MyTestClass#myMethod

# Mutation testing
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage
```

### Profiles

- `dev` — local development, connects to `localhost:5433`, seed data loaded, verbose SQL
- `staging` / `prod` — production-oriented configs (override via environment variables)

### Key endpoints

- `POST /api/auth/login` — staff login, returns JWT
- `POST /api/auth/candidate-token` — candidate assessment access token
- Candidate access under `/api/candidate/**` and `/api/take/**`
- Staff-only access under `/api/submissions/**` and other staff routes

SpringDoc OpenAPI UI is available at `/swagger-ui.html` when the app is running.

## Frontend (`recruitment-fe/`)

### Commands

```powershell
# Install dependencies
npm install

# Dev server (http://localhost:4200)
npm start

# Production build
npm run build

# Run tests (Vitest)
npm test

# Type-check without emitting
npx tsc --noEmit
```

### Routes

| Route | Description |
|-------|-------------|
| `/login` | Staff login |
| `/dashboard` | Dashboard |
| `/assessments` | Assessment list |
| `/assessments/new` | Create assessment |
| `/assessments/:id` | Edit assessment |
| `/assessments/:id/preview` | Preview assessment |
| `/questions` | Question bank |
| `/questions/new`, `/questions/:id/edit` | Create / edit question |
| `/candidates` | Candidate management |
| `/results` | Results list |
| `/flagged-submissions` | Submission flags |
| `/completed-assessments` | Completed assessments |
| `/staff` | Staff management |
| `/assessment/:id/take` | Candidate assessment taking (unauthenticated entry via password) |

## Testing

### Backend

- Unit tests run with Maven + JUnit 5
- Integration tests use TestContainers (PostgreSQL) and require Docker Desktop
- Mutation coverage is enforced with PIT (threshold configured in `pom.xml`)

> **Note:** On Docker Engine 29+, if integration tests fail with "Could not find a valid Docker environment", create or update `%USERPROFILE%\.docker-java.properties` with `api.version=1.44`.

**Last run: 2026-07-23 — 241/241 passing**

| Test class | Type | Tests |
|---|---|---|
| `AssessmentControllerIntegrationTest` | Integration | 27 |
| `AuthControllerIntegrationTest` | Integration | 5 |
| `AuthServiceTest` | Unit | 7 |
| `CandidateAuthControllerIntegrationTest` | Integration | 3 |
| `CandidateControllerIntegrationTest` | Integration | 7 |
| `CandidateHistoryIntegrationTest` | Integration | 4 |
| `CandidateHistoryServiceTest` | Unit | 7 |
| `CandidateServiceTest` | Unit | 8 |
| `CandidateTakeControllerIntegrationTest` | Integration | 12 |
| `CandidateTakeServiceTest` | Unit | 11 |
| `CodeExecutionControllerIntegrationTest` | Integration | 6 |
| `CodeExecutionServiceTest` | Unit | 11 |
| `DashboardControllerIntegrationTest` | Integration | 1 |
| `DashboardServiceTest` | Unit | 3 |
| `GlobalExceptionHandlerTest` | Unit | 5 |
| `InvitationControllerIntegrationTest` | Integration | 3 |
| `InvitationServiceTest` | Unit | 17 |
| `JwtServiceTest` | Unit | 4 |
| `MarkingIntegrationTest` | Integration | 11 |
| `MarkingServiceTest` | Unit | 8 |
| `QuestionControllerIntegrationTest` | Integration | 19 |
| `RecruitmentApplicationTests` | Integration | 1 |
| `ReminderControllerIntegrationTest` | Integration | 5 |
| `ReminderServiceTest` | Unit | 6 |
| `SubmissionFlagIntegrationTest` | Integration | 10 |
| `SubmissionFlagServiceTest` | Unit | 22 |
| `SubmissionServiceTest` | Unit | 15 |
| `TagControllerIntegrationTest` | Integration | 3 |
| **Total** | **28 classes** | **241** |

### Frontend

- Unit tests use Vitest with jsdom
- Type-checking is enforced via `npx tsc --noEmit`

**Last run: 2026-07-23 — 102/102 passing**

| Spec file | Tests |
|---|---|
| `app.spec.ts` | 1 |
| `core/auth/auth.service.spec.ts` | 7 |
| `core/candidate/candidate.service.spec.ts` | 3 |
| `core/flag/flag.service.spec.ts` | 7 |
| `core/marking/marking.service.spec.ts` | 4 |
| `core/take/candidate-take.service.spec.ts` | 3 |
| `features/assessments/assessment-detail.component.spec.ts` | 4 |
| `features/assessments/assessment-form.component.spec.ts` | 5 |
| `features/assessments/assessment-preview.component.spec.ts` | 4 |
| `features/assessments/assessment-take.component.spec.ts` | 14 |
| `features/candidates/candidates.component.spec.ts` | 12 |
| `features/dashboard/dashboard.component.spec.ts` | 4 |
| `features/flags/flagged-submissions.component.spec.ts` | 15 |
| `features/results/results.component.spec.ts` | 7 |
| `guards/auth.guard.spec.ts` | 2 |
| `shared/code-editor/code-editor.component.spec.ts` | 4 |
| `shared/code-runner/code-runner-panel.component.spec.ts` | 6 |
| **Total** | **17 files, 102** |

## CI / CD

The project uses GitHub Actions (`.github/workflows/ci.yml`) that runs on pushes and pull requests to `main`:

1. **Backend job**
   - Build with Maven
   - Run unit and integration tests
   - Run PIT mutation coverage
   - Upload mutation report as an artifact

2. **Frontend job**
   - Install dependencies with `npm ci`
   - Type-check with `tsc --noEmit`
   - Run tests with `npm test`

## Development notes

### Code execution with Piston

`CODE_SUBMISSION` questions compile and run candidate Java code in a self-hosted [Piston](https://github.com/engineer-man/piston) engine. The public emkc.org Piston API is whitelist-only, so the local container is required.

Piston is launched via Docker Compose with the `piston` service. The `piston-init` service installs the Java runtime on first run. A backend run with `./mvnw spring-boot:run` uses the default `PISTON_BASE_URL` of `http://localhost:2000/api/v2`.

### Database migrations

Flyway migrations are in `recruitment-be/src/main/resources/db/migration/`. Dev seed data lives in `db/seed/` and is only loaded in the `dev` profile.

### Windows JDK 24+ loopback fix

On Windows with JDK 24+, Tomcat can fail at startup with `Invalid argument: connect` because NIO pipes use Unix-domain sockets in the user temp directory. The `pom.xml` includes an activated `windows-nio-pipe-fix` profile that redirects `jdk.net.unixdomain.tmpdir` to `C:\Windows\Temp`.

### OpenSpec workflow

This project uses OpenSpec (`openspec/config.yaml`) with the `spec-driven` schema for AI-assisted feature development. Use `/openspec-new-change` or `/opsx:new` to start a structured change.

## License

This project is proprietary and not licensed for public distribution.
