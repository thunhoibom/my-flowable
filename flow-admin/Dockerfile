FROM node:24-alpine AS build

WORKDIR /workspace
ENV CI=true
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . ./
RUN pnpm build

FROM nginx:stable-alpine

# Backend URLs are read at container start (see nginx.conf.template); override per environment.
ENV FLOW_ENGINE_URL=http://flow-engine:8080 \
    FLOW_ADMIN_BE_URL=http://flow-admin-be:8081
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
