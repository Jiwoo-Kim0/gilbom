FROM node:24-alpine AS builder

WORKDIR /app

COPY gilbom/package.json gilbom/package-lock.json ./
RUN npm ci

COPY gilbom/index.html gilbom/eslint.config.js gilbom/tsconfig.json gilbom/tsconfig.app.json gilbom/tsconfig.node.json gilbom/vite.config.ts ./
COPY gilbom/public ./public
COPY gilbom/src ./src

# CI와 로컬 image가 같은 정적 검증을 수행하도록 lint를 build 앞에 둔다.
RUN npm run lint && npm run build

FROM nginx:1.30.4-alpine AS runtime

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
