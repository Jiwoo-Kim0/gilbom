# Node patch와 Alpine 세대까지 고정해 로컬·CI image 재빌드 결과를 일치시킨다.
FROM node:24.18.0-alpine3.23 AS builder

ARG VITE_NAVER_MAP_CLIENT_ID

WORKDIR /app

COPY gilbom/package.json gilbom/package-lock.json ./
# package-lock만 허용하고 선언된 Node/npm 버전이 다르면 즉시 실패한다.
RUN npm ci

COPY gilbom/index.html gilbom/eslint.config.js gilbom/tsconfig.json gilbom/tsconfig.app.json gilbom/tsconfig.node.json gilbom/vite.config.ts ./
COPY gilbom/public ./public
COPY gilbom/src ./src
COPY gilbom/test ./test

# CI와 로컬 image가 같은 test·lint·production build를 수행한다.
RUN test -n "$VITE_NAVER_MAP_CLIENT_ID" \
    && npm test \
    && npm run lint \
    && npm run build \
    && ! find dist -type f -name '*.map' -print -quit | grep -q .

FROM nginx:1.30.4-alpine AS runtime

COPY --chmod=0444 nginx/nginx.conf /etc/nginx/nginx.conf
COPY --chmod=0444 nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder --chown=0:101 /app/dist /usr/share/nginx/html
RUN find /usr/share/nginx/html -type d -exec chmod 0555 {} + \
    && find /usr/share/nginx/html -type f -exec chmod 0444 {} + \
    && nginx -t

USER nginx

EXPOSE 8080

STOPSIGNAL SIGQUIT

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --quiet --spider http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["nginx", "-g", "daemon off;"]
