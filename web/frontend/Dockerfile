# Node patch와 Alpine 세대까지 고정해 로컬·CI image 재빌드 결과를 일치시킨다.
FROM node:24.18.0-alpine3.23 AS builder

WORKDIR /app

COPY gilbom/package.json gilbom/package-lock.json ./
# package-lock만 허용하고 선언된 Node/npm 버전이 다르면 즉시 실패한다.
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
