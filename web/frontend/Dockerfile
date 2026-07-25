FROM node:24-alpine AS builder

WORKDIR /app

COPY gilbom/package.json gilbom/package-lock.json ./
RUN npm ci

COPY gilbom/index.html gilbom/tsconfig.json gilbom/tsconfig.app.json gilbom/tsconfig.node.json gilbom/vite.config.ts ./
COPY gilbom/public ./public
COPY gilbom/src ./src

RUN npm run build

FROM nginx:1.30.4-alpine AS runtime

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
