# flow-admin

Flow Admin: the shared frontend for the Design, Hub and Work areas. It calls `flow-admin-be` (Design, Hub) and `flow-engine` (Work).

Overall architecture: `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`.

## Features

- Process definition list, BPMN designer, plain XML import/export, deployment and version publishing
- Process instance list, tracking diagram, task/variable details, termination and task transfer
- Assignment rule CRUD with BPMN task-mode validation and version inheritance
- Tenant CRUD, enable/disable operations and enabled-tenant refresh
- Responsive workspace, typed API client, unit tests and Playwright workflow tests

## Stack

- Vue 3 and TypeScript
- Vite and pnpm
- Vue Router, Pinia, and TanStack Vue Query
- Element Plus and Lucide
- bpmn-js
- Vitest and Playwright

## Requirements

- Node.js 24 LTS
- pnpm 11

## Development

```bash
corepack pnpm install
corepack pnpm dev
```

The development server proxies `/api/engine` to Flow Engine (`http://localhost:8080`) and `/api/admin-be` to Flow Admin BE (`http://localhost:8081`). Override the targets through a local environment file based on `.env.example` (`VITE_ENGINE_PROXY_TARGET`, `VITE_ADMIN_BE_PROXY_TARGET`).

The Docker image renders `nginx.conf.template` at start-up from `FLOW_ENGINE_URL` and `FLOW_ADMIN_BE_URL`, so the same image works in every environment.

## Verification

```bash
corepack pnpm lint
corepack pnpm test:unit --run
corepack pnpm build
corepack pnpm test:e2e --project=chromium
```

发布前使用完整质量门禁：

```bash
corepack pnpm verify
```

`verify` 会依次执行格式检查、Lint、类型检查、单元测试、生产构建和 Chromium E2E。

## Documentation

- [Frontend architecture governance and roadmap](docs/quality/frontend-governance-and-roadmap.md)

## Security boundary

The browser uses the user access token returned by the authentication API. It never stores a
workflow service-client secret and never generates `X-Workflow-Token`; service authentication
belongs to trusted backend-to-backend integrations only.

Tenant-sensitive server state is cached with the active tenant code in its query key. Switching
tenants therefore creates a separate client-side cache boundary before data is refetched.
