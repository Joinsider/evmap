# Web client (ADR 0018): the Angular build, served by nginx, which also proxies /api/** to the API
# container so app and API share one origin (no CORS, no cookies).
FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY angular.json tsconfig*.json ./
COPY public public
COPY src src
RUN npx ng build

# Unprivileged variant: runs as uid 101 and listens on 8080, like the API. Alpine per ADR 0005.
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY --from=build /build/dist/evmap_web/browser /usr/share/nginx/html
COPY docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker/40-apple-app-site-association.sh /docker-entrypoint.d/40-apple-app-site-association.sh
# Where the upstream API lives inside the Compose network.
ENV API_UPSTREAM=http://api:8080
# Lets the image's entrypoint export the container's DNS servers as NGINX_LOCAL_RESOLVERS.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
EXPOSE 8080
