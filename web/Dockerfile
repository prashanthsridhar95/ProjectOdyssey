# Builds both web apps and serves them with nginx: admin on :5173, vendor on :5174.
FROM node:22-alpine AS build
WORKDIR /src
ARG VITE_API_URL=http://localhost:8080
ARG VITE_TENANT=carwash
ENV VITE_API_URL=$VITE_API_URL VITE_TENANT=$VITE_TENANT
COPY package.json package-lock.json tsconfig.base.json tsconfig.json ./
COPY packages ./packages
COPY apps ./apps
RUN npm ci --no-audit --no-fund && npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/apps/admin/dist /usr/share/nginx/admin
COPY --from=build /src/apps/vendor/dist /usr/share/nginx/vendor
EXPOSE 5173 5174
